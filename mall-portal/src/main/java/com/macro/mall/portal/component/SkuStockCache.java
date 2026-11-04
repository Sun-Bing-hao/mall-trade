package com.macro.mall.portal.component;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * SKU 可售库存的 Redis 预扣组件（下单库存的第一道防线）
 *
 * <p>作用：把「库存是否充足」的判断从数据库前移到 Redis，
 * 让绝大部分抢不到库存的请求在内存里就被拒绝，根本不产生数据库访问。
 *
 * <p>库存值定义为「可售量 = stock - lock_stock」，key 为 {@code mall:stock:sku:{skuId}}。
 *
 * <p>两级防线的分工：
 * <ul>
 *   <li><b>Redis（本类）</b>：性能层。原子预扣，负责拦截无效流量，不承担最终正确性；</li>
 *   <li><b>数据库（{@code PortalOrderDao.lockSkuStock}）</b>：正确性层。带条件的原子 UPDATE，
 *       即使 Redis 数据不准也绝不会超卖。</li>
 * </ul>
 * 正因为如此，Redis 出现故障时可以安全降级放行，由数据库兜底。
 *
 * <p>为什么用 {@link StringRedisTemplate} 而不是项目里的 {@code RedisService}：
 * 后者的 value 序列化器是 Jackson（带类型信息），存入的整数会变成带引号的 JSON，
 * Lua 脚本里 {@code tonumber()} 无法解析。库存计数必须是纯字符串数字。
 */
@Slf4j
@Component
public class SkuStockCache {

    /** 缓存 key 前缀 */
    private static final String KEY_PREFIX = "mall:stock:sku:";

    /**
     * 缓存有效期 30 分钟。
     * 设过期时间有两个目的：兜底 Redis 与数据库之间可能出现的偏差（例如后台直接改库存），
     * 以及避免长期不再销售的商品一直占着内存。
     */
    private static final long CACHE_TTL_SECONDS = 30 * 60L;

    /**
     * 预扣脚本。整段脚本在 Redis 单线程中执行，因此「读取-比较-扣减」天然原子，
     * 不会出现多条命令之间被其他请求插队的情况。
     *
     * <p>返回值：{@code 1} 预扣成功；{@code 0} 可售库存不足；{@code -1} key 不存在（需要先从数据库加载）。
     */
    private static final String DEDUCT_LUA = """
            local stock = redis.call('get', KEYS[1])
            if not stock then
                return -1
            end
            if tonumber(stock) < tonumber(ARGV[1]) then
                return 0
            end
            redis.call('decrby', KEYS[1], ARGV[1])
            return 1
            """;

    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT =
            new DefaultRedisScript<>(DEDUCT_LUA, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    /**
     * 尝试原子预扣库存。
     *
     * @return true 表示预扣成功（或 Redis 故障降级放行）；false 表示可售库存不足
     */
    public boolean tryDeduct(Long skuId, Integer quantity) {
        if (skuId == null || quantity == null || quantity <= 0) {
            return false;
        }
        String key = key(skuId);
        try {
            Long result = executeDeduct(key, quantity);
            if (result != null && result == -1L) {
                // 缓存未命中：从数据库加载可售量后重试一次
                initStock(skuId);
                result = executeDeduct(key, quantity);
                if (result != null && result == -1L) {
                    // 仍在加载中（或其他线程持有加载权），不能误判为售罄，
                    // 降级放行给数据库判定，避免把真实有货的商品拒掉
                    log.warn("库存缓存尚未就绪，降级交由数据库校验，skuId={}", skuId);
                    return true;
                }
            }
            return result != null && result == 1L;
        } catch (Exception e) {
            // 降级：Redis 不可用时放行，交给数据库的原子 UPDATE 做最终判断。
            // 代价是失去流量拦截效果，但下单能力不会中断，也不会超卖。
            log.error("库存预扣异常，降级交由数据库校验，skuId={}, quantity={}", skuId, quantity, e);
            return true;
        }
    }

    /**
     * 归还预扣的库存（下单失败、订单取消时调用）。
     * 只归还仍然存在的缓存：如果已过期，下次重建时会直接从数据库读到最新可售量，无需补偿。
     */
    public void rollback(Long skuId, Integer quantity) {
        if (skuId == null || quantity == null || quantity <= 0) {
            return;
        }
        try {
            String key = key(skuId);
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                stringRedisTemplate.opsForValue().increment(key, quantity);
            }
        } catch (Exception e) {
            // 补偿失败不影响主流程（数据库侧的库存已经处理完），留给对账任务修复
            log.error("库存归还失败，skuId={}, quantity={}", skuId, quantity, e);
        }
    }

    /**
     * 从数据库加载可售库存到缓存。
     *
     * <p>用一把短期的 SETNX 锁保证「只有一个线程查数据库」：
     * 缓存刚过期或首次访问时，如果所有并发请求同时回源，会把数据库打满（缓存击穿），
     * 而且 300ms 的 Redis 命令超时很容易被拖爆。
     * 抢不到加载权的线程直接返回，由调用方决定降级放行。
     */
    public void initStock(Long skuId) {
        String key = key(skuId);
        String loadLockKey = key + ":loading";
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(loadLockKey, "1", Duration.ofSeconds(3));
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        try {
            PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(skuId);
            if (skuStock == null) {
                return;
            }
            int available = Math.max(0, skuStock.getStock() - skuStock.getLockStock());
            // SETNX：即使加载期间有别的线程写入了新值，也不会被覆盖
            stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(available), Duration.ofSeconds(CACHE_TTL_SECONDS));
        } finally {
            stringRedisTemplate.delete(loadLockKey);
        }
    }

    /** 读取缓存中的可售库存，仅用于验证与排查 */
    public Integer getCachedStock(Long skuId) {
        String value = stringRedisTemplate.opsForValue().get(key(skuId));
        return value == null ? null : Integer.valueOf(value);
    }

    private Long executeDeduct(String key, Integer quantity) {
        return stringRedisTemplate.execute(DEDUCT_SCRIPT, Collections.singletonList(key),
                String.valueOf(quantity));
    }

    /** 库存缓存的 key，对外暴露便于排查与清理 */
    public static String cacheKey(Long skuId) {
        return KEY_PREFIX + skuId;
    }

    private String key(Long skuId) {
        return cacheKey(skuId);
    }
}
