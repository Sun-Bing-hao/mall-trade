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
import java.util.UUID;

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
     * 缓存加载锁（loading key）的超时时间。
     * 首要作用是防止持锁进程崩溃后锁永久不释放；正常路径靠 finally 主动解锁。
     * 取值要远大于一次数据库查询的耗时（毫秒级），留足 1000 倍余量。
     */
    private static final long LOAD_LOCK_TTL_SECONDS = 10L;

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

    /**
     * 解锁脚本：只有锁的值仍然是自己写入的 token 时才删除。
     *
     * <p>为什么不能先 {@code get} 再 {@code delete}：两条命令之间锁可能刚好过期并被别的线程抢走，
     * 这时删掉的就是别人的锁，会导致更多线程重复回源。放进 Lua 里，Redis 单线程执行整段脚本，
     * 「比较 + 删除」成为一个原子操作。
     */
    private static final String UNLOCK_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

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
     *
     * <p>锁的值用 UUID 而不是固定字符串：解锁时要能确认「这把锁还是我加的」。
     * 否则持锁线程执行超过 TTL 后，它的 finally 会把后来者的锁删掉（误删他人锁）。
     * 没有用线程 id 是因为它跨 JVM 会重复，而锁的持有者是「一次请求」而非「一个线程」。
     */
    public void initStock(Long skuId) {
        String key = key(skuId);
        String loadLockKey = key + ":loading";
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(loadLockKey, token, Duration.ofSeconds(LOAD_LOCK_TTL_SECONDS));
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
            unlock(loadLockKey, token);
        }
    }

    /**
     * 释放加载锁。用 Lua 保证「比对 token + 删除」的原子性。
     * 解锁失败不能影响主流程（锁本身有 TTL 会自行过期），所以这里吞掉异常。
     *
     * @param lockKey 锁的 key（{@code mall:stock:sku:{skuId}:loading}）
     * @param token   加锁时写入的唯一标识，只有与当前锁值一致才会删除
     */
    public void unlock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            log.warn("释放库存加载锁失败，等待自动过期，lockKey={}", lockKey, e);
        }
    }

    /** 读取缓存中的可售库存，仅用于验证与排查 */
    public Integer getCachedStock(Long skuId) {
        String value = stringRedisTemplate.opsForValue().get(key(skuId));
        return value == null ? null : Integer.valueOf(value);
    }

    /**
     * 用数据库的可售量覆盖缓存（对账修正用）。
     * 顺便重置 TTL：既然刚和数据库对齐过，重新计时是合理的。
     */
    public void reset(Long skuId, int available) {
        stringRedisTemplate.opsForValue()
                .set(key(skuId), String.valueOf(Math.max(0, available)),
                        Duration.ofSeconds(CACHE_TTL_SECONDS));
    }

    /** 清除某个 SKU 的库存缓存（SKU 被删除时使用） */
    public void evict(Long skuId) {
        stringRedisTemplate.delete(key(skuId));
    }

    /**
     * 从缓存 key 解析出 skuId，解析失败返回 null。
     * 注意 {@code mall:stock:sku:110:loading} 这类内部 key 也会被 SCAN 匹配到，
     * 这里通过「后缀必须是纯数字」把它们过滤掉。
     */
    public static Long parseSkuId(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(key.substring(KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
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
