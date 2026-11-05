package com.macro.mall.portal.component;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存缓存对账任务。
 *
 * <p>Redis 里的可售量是从数据库派生的副本，两者之间没有分布式事务，以下场景都会让它们产生偏差：
 * <ul>
 *   <li>事务回滚时归还 Redis 失败（网络抖动、Redis 短暂不可用）；</li>
 *   <li>后台管理端直接修改了 SKU 库存，缓存不知情；</li>
 *   <li>Redis 重启或 key 被误删后，用旧数据重建；</li>
 *   <li>缓存重建时正好有未提交的订单，读到的是旧的可售量。</li>
 * </ul>
 *
 * <p>本任务定时扫描所有库存缓存，与数据库的 {@code stock - lock_stock} 比对，
 * 发现偏差就以数据库为准修正。这样偏差的存活时间从「缓存 TTL 30 分钟」
 * 缩短到「一个对账周期 1 分钟」。
 *
 * <p>扫描用 {@code SCAN} 而不是 {@code KEYS}：{@code KEYS} 会阻塞 Redis 单线程，
 * 在缓存数量多时可能造成整个实例卡顿。
 *
 * <p>安全性：修正方向永远是「向数据库靠拢」，数据库是权威。即使正好赶上某笔订单
 * 尚未提交，把缓存改得偏高，也只会让多几个请求落到数据库，由条件 UPDATE 拒绝，
 * 不会造成超卖。
 */
@Slf4j
@Component
public class StockReconcileTask {

    private static final String KEY_PATTERN = "mall:stock:sku:*";

    /** 单次 SCAN 建议返回的 key 数量，不是硬性上限 */
    private static final int SCAN_BATCH = 500;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SkuStockCache skuStockCache;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    /**
     * 定时入口。默认每分钟执行一次，可用 mall.stock.reconcile-interval-ms 覆盖。
     * initialDelay 留 30 秒，避开应用启动时正在进行的缓存预热。
     */
    @Scheduled(fixedDelayString = "${mall.stock.reconcile-interval-ms:60000}", initialDelay = 30000)
    public void scheduledReconcile() {
        try {
            ReconcileResult result = reconcile();
            if (result.fixed() > 0 || result.evicted() > 0) {
                log.warn("库存对账：扫描 {} 个缓存，修正 {} 个偏差，清理 {} 个失效缓存",
                        result.scanned(), result.fixed(), result.evicted());
            }
        } catch (Exception e) {
            // 定时任务不能把异常抛出去，否则后续调度会被取消
            log.error("库存对账任务执行失败", e);
        }
    }

    /**
     * 执行一次全量对账。
     *
     * @return 扫描数量、修正数量、清理数量
     */
    public ReconcileResult reconcile() {
        int scanned = 0;
        int fixed = 0;
        int evicted = 0;

        ScanOptions options = ScanOptions.scanOptions().match(KEY_PATTERN).count(SCAN_BATCH).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // 过滤掉 mall:stock:sku:110:loading 这类内部 key
                Long skuId = SkuStockCache.parseSkuId(key);
                if (skuId == null) {
                    continue;
                }
                scanned++;
                Integer cached = skuStockCache.getCachedStock(skuId);
                if (cached == null) {
                    // 扫描过程中恰好过期，跳过
                    continue;
                }
                PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(skuId);
                if (skuStock == null) {
                    // SKU 已被删除，缓存没有存在意义
                    skuStockCache.evict(skuId);
                    evicted++;
                    log.warn("库存对账：SKU {} 已不存在，清理其缓存", skuId);
                    continue;
                }
                int available = Math.max(0, skuStock.getStock() - skuStock.getLockStock());
                if (cached != available) {
                    skuStockCache.reset(skuId, available);
                    fixed++;
                    log.warn("库存对账：修正偏差 skuId={}, 缓存={}, 数据库可售={}", skuId, cached, available);
                }
            }
        }
        return new ReconcileResult(scanned, fixed, evicted);
    }

    /**
     * @param scanned 本次扫描到的有效缓存数量
     * @param fixed   被修正的偏差数量
     * @param evicted 被清理的失效缓存数量
     */
    public record ReconcileResult(int scanned, int fixed, int evicted) {
    }
}
