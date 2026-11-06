# mall-portal 代码地图

> 用途：看到功能能定位到类，动手改之前能预判波及范围。  
> 数据来源：`mall-portal/src/main/java` 下 86 个 Java 文件（实际扫描，非凭印象）。



---

## 一、九个包的分层职责

| 包              | 文件数 | 职责                                         | 判断规则（看到什么放这里）                         |
| -------------- | --- | ------------------------------------------ | ------------------------------------- |
| `controller`   | 13  | HTTP 入口，只做参数接收 + 调 service + 包装返回          | 有 `@RestController`、`@RequestMapping` |
| `service`      | 15  | 业务接口定义                                     | 只有方法签名，`XxxService`                   |
| `service.impl` | 15  | 业务实现，真正干活的地方                               | `XxxServiceImpl`，`@Service`           |
| `dao`          | 5   | 自定义 SQL（跨表查询、批量更新），配 `resources/dao/*.xml` | 接口方法在 MBG 生成的 Mapper 里找不到时            |
| `domain`       | 19  | portal 专用的 DTO / 聚合对象 / 内部模型               | 不属于数据库表映射的类                           |
| `component`    | 5   | 非 HTTP 触发的后台组件（MQ 生产/消费、定时任务、缓存）           | `@Component` 且不被 Controller 调用        |
| `config`       | 9   | 各类配置（MQ、安全、支付宝、Swagger、CORS、Jackson）       | `@Configuration`                      |
| `repository`   | 3   | MongoDB 数据访问                               | 继承 `MongoRepository`                  |
| `util`         | 1   | 工具类                                        | `DateUtil`                            |

**注意**：数据库单表操作不在这里。76 张表的 `XxxMapper` + `Xxx` 实体全部由 `mall-mbg` 模块的 MBG 生成，portal 直接注入使用。**只有跨表/批量的手写 SQL 才放在 `dao` 包**。

---

## 二、功能 → 类 映射

### 1. 商品浏览

| 功能             | Controller                   | Service                       | 数据访问                    | 主要表                                        |
| -------------- | ---------------------------- | ----------------------------- | ----------------------- | ------------------------------------------ |
| 首页内容（轮播/推荐/秒杀） | `HomeController`             | `HomeServiceImpl`             | `HomeDao`、各 mapper      | `sms_home_advertise`、`sms_flash_promotion` |
| 商品列表/搜索/详情     | `PmsPortalProductController` | `PmsPortalProductServiceImpl` | `PortalProductDao`      | `pms_product`、`pms_sku_stock`              |
| 商品分类树          | 同上                           | 同上                            | `productCategoryMapper` | `pms_product_category`                     |
| 品牌列表/详情        | `PmsPortalBrandController`   | `PmsPortalBrandServiceImpl`   | `HomeDao`               | `pms_brand`                                |

### 2. 购物车与促销

| 功能             | Controller              | Service                                                            | 关键类                 |
| -------------- | ----------------------- | ------------------------------------------------------------------ | ------------------- |
| 加购/改数量/删除/列表   | `OmsCartItemController` | `OmsCartItemServiceImpl`                                           | `OmsCartItem`       |
| 购物车算促销价        | 同上                      | `OmsCartItemServiceImpl.listPromotion` → `OmsPromotionServiceImpl` | `CartPromotionItem` |
| 促销规则（满减/阶梯/秒杀） | 无独立接口                   | `OmsPromotionServiceImpl`                                          | `PortalProductDao`  |

**这里有个容易忽略的点**：`OmsCartItemServiceImpl` 依赖 `OmsPromotionServiceImpl`，而订单模块又依赖 `OmsCartItemServiceImpl`。所以改促销计算逻辑，购物车和下单会同时受影响。

### 3. 订单（核心，风险最高）

| 功能          | Controller                 | Service（全部在 `OmsPortalOrderServiceImpl`） |
| ----------- | -------------------------- | ---------------------------------------- |
| 确认单（算价不落库）  | `OmsPortalOrderController` | `generateConfirmOrder`                   |
| 下单          | 同上                         | `generateOrder` → `lockStock`            |
| 支付成功回调      | 同上                         | `paySuccess` → `updateSkuStock`          |
| 取消订单（手动/MQ） | 同上                         | `cancelOrder` → `releaseSkuStockLock`    |
| 超时批量取消（定时）  | 无接口                        | `cancelTimeOutOrder`                     |
| 订单列表/详情/删除  | 同上                         | `list` / `detail` / `delete`             |
| 确认收货        | 同上                         | `confirmReceiveOrder`                    |

**`OmsPortalOrderServiceImpl` 注入了 15 个依赖**，是全项目最重的类：

```
memberService, cartItemService, memberReceiveAddressService, memberCouponService,
integrationConsumeSettingMapper, couponHistoryDao, orderMapper, orderItemDao,
couponHistoryMapper, redisService, portalOrderDao, orderSettingMapper,
orderItemMapper, cancelOrderSender, skuStockCache
```

### 4. 支付

| 功能      | 类                                               | 说明                                   |
| ------- | ----------------------------------------------- | ------------------------------------ |
| 发起支付宝支付 | `AlipayController` → `AlipayServiceImpl.pay`    | 依赖 `portalOrderService`              |
| 支付异步回调  | `AlipayController.notify` → `AlipayServiceImpl` | 之后调回 `portalOrderService.paySuccess` |

**支付模块反向依赖订单模块**，改 `paySuccess` 会直接影响支付回调。

### 5. 会员

| 功能              | Controller                          | Service                              | 说明                                                       |
| --------------- | ----------------------------------- | ------------------------------------ | -------------------------------------------------------- |
| 登录/注册/改密/获取当前用户 | `UmsMemberController`               | `UmsMemberServiceImpl`               | 依赖 `jwtTokenUtil`、`passwordEncoder`、`memberCacheService` |
| 会员信息缓存          | 无接口                                 | `UmsMemberCacheServiceImpl`          | Redis 缓存，`@CacheException` 控制是否抛异常                       |
| 收货地址            | `UmsMemberReceiveAddressController` | `UmsMemberReceiveAddressServiceImpl` | 下单时按 id 取                                                |
| 商品收藏            | `MemberProductCollectionController` | `MemberCollectionServiceImpl`        | **MongoDB**                                              |
| 品牌关注            | `MemberAttentionController`         | `MemberAttentionServiceImpl`         | **MongoDB**                                              |
| 浏览记录            | `MemberReadHistoryController`       | `MemberReadHistoryServiceImpl`       | **MongoDB**                                              |

### 6. 营销与售后

| 功能                 | Controller                            | Service                                |
| ------------------ | ------------------------------------- | -------------------------------------- |
| 我的优惠券 / 领取 / 下单可用券 | `UmsMemberCouponController`           | `UmsMemberCouponServiceImpl`           |
| 退货申请               | `OmsPortalOrderReturnApplyController` | `OmsPortalOrderReturnApplyServiceImpl` |

---

## 三、下单链路完整穿越

```
POST /order/generateOrder  { memberReceiveAddressId, payType, cartIds }
│
├─ OmsPortalOrderController.generateOrder              （@RequestMapping("/order")）
│
└─ OmsPortalOrderServiceImpl.generateOrder             @Transactional
   ├─ 1. 校验收货地址为空 → Asserts.fail
   ├─ 2. memberService.getCurrentMember()               从 SecurityContext 取登录用户
   ├─ 3. cartItemService.listPromotion()                查购物车 + 实时算促销价
   │     └─ OmsPromotionServiceImpl.calcCartPromotion
   ├─ 4. 组装 OmsOrderItem（订单快照：名称/价格/图片全部复制一份）
   ├─ 5. hasStock()                                     预判，非安全边界
   ├─ 6. 优惠券 / 积分分摊
   ├─ 7. lockStock()                       ★ 两级防线
   │     ├─ SkuStockCache.tryDeduct()     Redis + Lua 预扣（性能层）
   │     ├─ PortalOrderDao.lockSkuStock() DB 条件更新（正确性层）
   │     └─ 注册事务回滚回调 → 失败时归还 Redis
   ├─ 8. generateOrderSn()                              Redis INCR 生成订单号
   ├─ 9. orderMapper.insert(oms_order)                  写订单主表
   ├─ 10. orderItemDao.insertList(oms_order_item)       写订单明细
   ├─ 11. cartItemService.clear()                       软删购物车
   └─ 12. cancelOrderSender.sendMessage()               发延迟消息（TTL 队列）
```

**取消链路（MQ 触发）**：

```
mall.order.cancel.ttl  ──TTL到期──▶  mall.order.cancel
   └─ CancelOrderReceiver.handle(orderId)
      └─ portalOrderService.cancelOrder()
         ├─ 查 status=0 的订单，查不到直接 return（幂等）
         ├─ 更新 status=4
         ├─ portalOrderDao.releaseSkuStockLock()   DB lock_stock 减
         └─ releaseCacheStock()                     Redis 可售量加
```

---

## 四、改动影响范围表（最重要）

| 你要改什么        | 必改                                                        | 连带检查                                                          | 最容易漏的地方                                                      |
| ------------ | --------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------ |
| **库存锁定逻辑**   | `OmsPortalOrderServiceImpl.lockStock`                     | `SkuStockCache`、`PortalOrderDao.lockSkuStock`                 | `cancelOrder` / `cancelTimeOutOrder` 的归还；`paySuccess` 的可售量不变 |
| **订单超时时间**   | `oms_order_setting.normal_order_overtime`                 | `sendDelayMessageCancelOrder`                                 | 已入队消息的 TTL 不会变，只影响新单                                         |
| **延迟消息实现**   | `RabbitMqConfig`、`QueueEnum`、`CancelOrderSender/Receiver` | `CancelOrderReceiver` 里硬编码的队列名                                | `@RabbitListener(queues = "mall.order.cancel")` 没引用枚举        |
| **下单算价/促销**  | `OmsPromotionServiceImpl`                                 | `OmsCartItemServiceImpl.listPromotion`、`generateConfirmOrder` | 购物车展示价与下单价必须同源                                               |
| **会员登录**     | `UmsMemberServiceImpl.login`                              | `JwtTokenUtil`、`SecurityConfig`、`UmsMemberCacheServiceImpl`   | JWT 密钥在 yml 里，改了所有旧 token 失效                                 |
| **收货地址**     | `UmsMemberReceiveAddressServiceImpl`                      | `generateOrder` 里的地址校验、订单快照字段                                 | 只判空不校验归属，可以传别人地址                                             |
| **支付回调**     | `AlipayServiceImpl` → `paySuccess`                        | `PortalOrderDao.updateSkuStock`                               | 该 SQL 没有 `stock>=quantity` 条件                                |
| **新增一个接口**   | `controller` + `service` + `service.impl`                 | `application.yml` 的 `secure.ignored.urls`（免登录白名单）             | 忘了加白名单会 401                                                  |
| **新增一张表的查询** | `mall-mbg` 的 Mapper                                       | `dao` 包 + `resources/dao/*.xml`（跨表时）                          | MBG 重新生成会覆盖手写代码                                              |

---

## 五、定位一个功能的通用三步法

1. **从 URL 找 Controller**：URL 前缀 = 类上 `@RequestMapping`，方法上的注解确定具体方法
2. **从 Controller 找 Service**：看注入的字段名，`Ctrl+Alt+B`（Jump to Implementation）跳到实现类
3. **从 Service 找数据**：单表操作用 `mall-mbg` 的 `XxxMapper`，跨表用 `dao` 包的 `XxxDao` + `resources/dao/XxxDao.xml`

---

## 六、建议的阅读顺序

| 顺序 | 目标         | 涉及类                                                                               | 预计     |
| -- | ---------- | --------------------------------------------------------------------------------- | ------ |
| 1  | 一个请求怎么进来的  | `MallPortalApplication` → `MallSecurityConfig` → `JwtAuthenticationTokenFilter`   | 30 min |
| 2  | 会员从登录到被识别  | `UmsMemberController` → `UmsMemberServiceImpl` → `UmsMemberCacheServiceImpl`      | 1 h    |
| 3  | 商品怎么查出来    | `PmsPortalProductController` → `PmsPortalProductServiceImpl` → `PortalProductDao` | 1 h    |
| 4  | 购物车怎么算价    | `OmsCartItemServiceImpl` → `OmsPromotionServiceImpl` → `CartPromotionItem`        | 1.5 h  |
| 5  | **下单（核心）** | `OmsPortalOrderServiceImpl.generateOrder` 全流程                                     | 3 h    |
| 6  | 订单怎么被取消    | `RabbitMqConfig` → `CancelOrderSender` → `CancelOrderReceiver` → `cancelOrder`    | 1.5 h  |
| 7  | 支付回调       | `AlipayServiceImpl` → `paySuccess`                                                | 1 h    |

---

## 七、这个模块已知的问题（改造清单）

| #  | 问题                            | 位置                               | 影响                             | 状态                      |
| -- | ----------------------------- | -------------------------------- | ------------------------------ | ----------------------- |
| 1  | 库存读-改-写竞态                     | `lockStock`                      | 超卖                             | ✅ 已修（原子 SQL + Redis 预扣） |
| 2  | 类上无事务注解                       | `OmsPortalOrderServiceImpl`      | 锁库存成功但订单失败时库存泄漏                | ✅ 已修（`@Transactional`）  |
| 3  | 任何异常被伪装成 401                  | `secure.ignored.urls` 缺 `/error` | 排查困难                           | ⬜ 待修                    |
| 4  | 延迟队列队头阻塞                      | TTL + 死信方案                       | 超时取消不准时                        | ⬜ 待修                    |
| 5  | 消费无幂等、无重试、无兜底                 | `CancelOrderReceiver`            | 消息重复/丢失无感知                     | ⬜ 待修                    |
| 6  | 订单号强依赖 Redis，`order_sn` 无唯一索引 | `generateOrderSn`                | Redis 故障则无法下单；计数丢失产生重复单号       | ⬜ 待修                    |
| 7  | 订单快照字段依赖前端传参                  | `OmsCartItemServiceImpl.add`     | 客户端可篡改价格；`oms_order_item` 名称为空 | ⬜ 待修                    |
| 8  | 收货地址不校验归属                     | `generateOrder`                  | 可用他人地址下单                       | ⬜ 待修                    |
| 9  | `/sso/info` 返回实体泄露密码哈希        | `UmsMemberController.info`       | 信息泄露                           | ⬜ 待修                    |
| 10 | 加载锁无 owner 校验（已修）             | `SkuStockCache.initStock`        | 误删他人锁                          | ✅ 已修（UUID + Lua）        |
