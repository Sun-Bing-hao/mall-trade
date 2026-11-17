# mall 电商交易系统 · 交易链路并发改造

> 基于开源项目 [macrozheng/mall](https://github.com/macrozheng/mall) 二次开发，聚焦「下单 — 支付 — 取消」主链路的**并发安全**与**可靠性**改造。

原项目是一套功能完整的 B2C 电商系统，包含后台管理、前台商城、商品搜索等服务。本仓库在其基础上，针对交易链路中若干**会产生生产事故**的问题做了系统性改造，并补充了并发回归测试。

---

## 改造概览

| 模块 | 原问题 | 改造手段 | 效果 |
|---|---|---|---|
| **库存并发** | 「查询-修改-写回」三步无原子性，200 并发下仅存留 3 个锁定量 | 条件更新合并校验与扣减 + Redis/Lua 预扣 + 定时对账 | 0 超卖，吞吐 269 → 649 req/s |
| **缓存一致性** | Redis 与 MySQL 跨数据源，任一侧失败都会留下偏差 | 事务补偿 + 取消同步归还 + 定时对账，三层收敛 | 不一致窗口从 TTL 的 30 分钟降至 1 分钟 |
| **超时取消** | 并发与消息重投导致重复释放库存；消息丢失则订单永久待付款 | 状态机 CAS + 事务 + 消费重试/失败转移 + 数据兜底扫描 | 四层保障闭环 |
| **支付回调** | 已取消订单收到迟到回调会被「复活」为已支付，并重复扣减库存 | 状态机 CAS，拒绝非法状态流转 | 消除资损隐患 |

---

## 核心改造详解

### 一、库存并发安全

**问题**：原实现是典型的三步操作——

```java
PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(skuId);  // 读
skuStock.setLockStock(skuStock.getLockStock() + quantity);        // 改（内存）
skuStockMapper.updateByPrimaryKeySelective(skuStock);             // 写回
```

单线程正确，并发下两个请求读到同一个旧值，后写的覆盖先写的。压测 200 并发各买 1 件（库存 100），最终 `lock_stock` 只有 **3**，丢失率 98.5%。

**改造分三步，每一步解决不同层次的问题：**

**1. 条件更新 —— 先保证正确**

```sql
UPDATE pms_sku_stock
   SET lock_stock = lock_stock + #{quantity}
 WHERE id = #{skuId}
   AND stock - lock_stock >= #{quantity}
```

把「判断库存是否充足」和「扣减」合并为一条 SQL，由 InnoDB 行锁保证原子性；返回 0 即表示库存不足。同时补上了方法级 `@Transactional`——原实现没有事务，锁库存成功但订单落库失败时会造成库存永久泄漏。

**2. Redis + Lua 预扣 —— 再优化性能**

正确性解决后，瓶颈变成「所有请求都打数据库」。引入 Redis 预扣，用 Lua 脚本保证判断与扣减原子：

```lua
local stock = redis.call('get', KEYS[1])
if not stock then return -1 end                              -- key 不存在，回源加载
if tonumber(stock) < tonumber(ARGV[1]) then return 0 end     -- 库存不足，直接拒绝
redis.call('decrby', KEYS[1], ARGV[1])
return 1
```

**Redis 只承担性能职责，正确性始终由数据库条件更新保证**——因此 Redis 故障时可以安全降级放行，不会超卖。另配合 SETNX 互斥回源防止缓存击穿。

**3. 定时对账 —— 收敛偏差**

跨数据源不存在分布式事务，偏差无法完全避免。每 5 分钟扫描缓存 key，与数据库 `stock - lock_stock` 比对，不一致则以数据库为准覆盖。修正方向恒为「向数据库靠拢」，保证最坏情况是缓存偏高（多放几个请求进数据库被拒），不会偏低导致少卖。

**验证**：`StockLockAtomicTest`（200 并发，断言 0 超卖）、`StockRedisDeductTest`（吞吐与拦截率）、`StockReconcileTest`（偏差修正）。

---

### 二、缓存一致性

偏差有四个来源：下单事务回滚、归还 Redis 失败、后台直接改库存、缓存重建时读到未提交数据。针对不同阶段分别处理：

| 场景 | 手段 | 时机 |
|---|---|---|
| 下单过程中失败 | catch 中立即归还 | 失败已确定 |
| 下单后续步骤失败（如订单落库） | 注册事务回调归还 | `afterCompletion == ROLLED_BACK` |
| 订单取消 | 归还推迟到 `afterCommit` | 事务提交成功后才执行 |
| 残留偏差 | 定时对账修正 | 每 5 分钟 |

**关键设计**：失败确定时立即补偿，成功确定后才执行补偿。若取消订单时在事务内直接归还，一旦事务回滚就会出现「数据库仍占用、缓存已释放」的可售量虚高。

归还前还会检查 key 是否存在——`INCR` 对不存在的 key 会从 0 创建，若缓存已过期时直接 `increment`，会让该 SKU 的可售量凭空变成 1。

---

### 三、订单超时取消链路

原实现用「先查 status=0 再更新」，并发或消息重投时会重复释放库存。改造后形成四层保障：

| 层 | 手段 | 解决的问题 |
|---|---|---|
| 1 | 状态机 CAS：`UPDATE ... WHERE status = 0` + 判断影响行数 | 重复调用、并发取消 |
| 2 | `@Transactional` 包裹状态变更与库存/优惠券/积分回退 | 中途失败导致数据不一致 |
| 3 | 消费端重试 3 次（1s/2s 退避）+ 失败转投兜底队列 | 异常消息无限重投堵塞消费者 |
| 4 | 定时扫描数据库中超时未支付订单，逐笔走同一套取消逻辑 | 消息丢失导致订单永久待付款 |

第 4 层是**从「消息驱动」到「状态驱动」的转变**：消息可能丢、可能重复、可能乱序，但数据库里「status=0 且创建时间早于某时刻」这个事实不会骗人。

扫描任务的设计细节：
- **宽限期**：阈值 = 超时时间 + 5 分钟，让延迟消息先有机会处理，避免无效竞争
- **组合索引**：`(status, delete_status, create_time)`，实测执行计划由 `ALL` 变为 `range`，扫描行数与订单总量无关
- **时间下界**：只扫最近 7 天，防止历史脏数据每轮占满批次名额
- **逐笔隔离**：单笔失败不影响同批其他订单

---

### 四、支付回调状态校验

**问题**：`paySuccess` 使用 `updateByPrimaryKeySelective` 直接改状态，没有任何条件——

一笔已超时取消（`status=4`）的订单收到迟到的支付回调时，会被改成「已支付」，同时真实库存被扣减、`lock_stock` 被减为**负数**。用户付了钱、订单已关闭、货已卖给别人。

**改造**：改为专用 SQL + 状态机 CAS，影响行数为 0 时直接返回，不执行任何库存操作；同时补充事务，保证状态流转与库存扣减同生共死。重复回调（支付平台重推）天然幂等。

此外收敛了 `/order/paySuccess` 这个对外暴露的接口——真实支付回调走 `/alipay/notify`，该接口属于多余入口，且可被用于伪造支付。

---

## 技术栈

| 分类 | 技术 |
|---|---|
| 语言 / 框架 | Java 17、Spring Boot 3.5、MyBatis、Spring Security + JWT |
| 数据存储 | MySQL 8（交易数据）、Redis（缓存）、MongoDB（收藏/关注/浏览记录）、Elasticsearch（商品搜索，独立服务） |
| 消息中间件 | RabbitMQ（TTL + 死信实现延迟任务、消费重试与失败转移） |
| 构建 / 测试 | Maven、JUnit 5、Docker |

## 项目结构

```
mall
├── mall-common      公共模块：统一返回、全局异常、Redis 封装
├── mall-mbg         MyBatis Generator 生成的实体与 Mapper（76 张表）
├── mall-security    JWT 认证与动态 RBAC 权限
├── mall-admin       后台管理服务（8080）
├── mall-portal      前台商城服务（8085）  ← 本次改造主要集中在此
├── mall-search      商品搜索服务（8081，依赖 Elasticsearch）
└── document         数据库脚本、设计文档
```

## 本地运行

**依赖**：MySQL 8、Redis、RabbitMQ、MongoDB（MongoDB 仅影响收藏/关注/浏览记录，不影响交易链路）

```bash
# 1. 启动中间件
docker run -d --name redis   -p 6379:6379  redis:7 --requirepass 123456
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=mall -e RABBITMQ_DEFAULT_PASS=mall rabbitmq:3.10-management
docker run -d --name mongo   -p 27017:27017 mongo:6

# 2. 初始化数据库
mysql -uroot -p < document/sql/mall.sql
mysql -uroot -p < document/sql/2026-11-12-add-order-scan-index.sql

# 3. 启动服务
mvn clean package -DskipTests
java -jar mall-portal/target/mall-portal.jar
```

启动后访问 `http://localhost:8085/swagger-ui.html` 调试接口，RabbitMQ 管理台在 `http://localhost:15672`（mall/mall）。

> **端口提示**：若本机 5672 被 Windows 保留端口区间占用（`netsh interface ipv4 show excludedportrange protocol=tcp` 可查），需将容器映射改为其他端口，并同步修改 `application-dev.yml`。

## 已知未完成项

以下问题已识别，权衡投入产出后暂未处理：

- **延迟队列的队头阻塞**：TTL + 死信方案下，同一队列中长 TTL 消息会阻塞后续短 TTL 消息。彻底解决需改用 RocketMQ 延迟消息或 RabbitMQ 延迟插件。
- **订单快照依赖前端传参**：`oms_order_item` 的商品名称、价格等字段由客户端传入，理论上可篡改，应由服务端回查商品表。
- **下单接口未做幂等**：用户重复提交会生成多笔订单，可引入业务唯一键或 token 机制。
- **消息发送未开启 publisher confirm**：当前依赖数据库兜底扫描保证最终结果，可补充 confirm 以缩短故障发现时间。

## 致谢

原项目 [macrozheng/mall](https://github.com/macrozheng/mall) 提供了完整的电商业务骨架与工程结构，本仓库在此基础上进行交易链路的改造实践。遵循 Apache License 2.0。
