package com.macro.mall.portal;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.component.SkuStockCache;
import com.macro.mall.portal.component.StockReconcileTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存对账任务测试。
 *
 * <p>验证四件事：
 * <ul>
 *   <li>缓存值偏高时，对账把它压回数据库值；</li>
 *   <li>缓存值偏低时，对账把它拉回数据库值；</li>
 *   <li>两者一致时，不做任何修改；</li>
 *   <li>SKU 已删除时，清理其缓存。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 关掉定时调度对测试的干扰（initialDelay 30s，测试期间通常不会触发，这里再调大周期）
        "mall.stock.reconcile-interval-ms=3600000"
})
public class StockReconcileTest {

    private static final Long SKU_ID = 110L;
    private static final Long GHOST_SKU_ID = 999999L;
    private static final int STOCK = 100;

    @Autowired
    private StockReconcileTask reconcileTask;

    @Autowired
    private SkuStockCache skuStockCache;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private int originStock;
    private int originLockStock;

    @BeforeEach
    public void prepare() {
        PmsSkuStock origin = skuStockMapper.selectByPrimaryKey(SKU_ID);
        originStock = origin.getStock();
        originLockStock = origin.getLockStock();

        PmsSkuStock target = new PmsSkuStock();
        target.setId(SKU_ID);
        target.setStock(STOCK);
        target.setLockStock(0);
        skuStockMapper.updateByPrimaryKeySelective(target);

        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        stringRedisTemplate.delete(SkuStockCache.cacheKey(GHOST_SKU_ID));
        skuStockCache.initStock(SKU_ID);
        System.out.println("准备完成：SKU " + SKU_ID + " 库存=" + STOCK + "，缓存="
                + skuStockCache.getCachedStock(SKU_ID));
    }

    @AfterEach
    public void restore() {
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        stringRedisTemplate.delete(SkuStockCache.cacheKey(GHOST_SKU_ID));
        PmsSkuStock restore = new PmsSkuStock();
        restore.setId(SKU_ID);
        restore.setStock(originStock);
        restore.setLockStock(originLockStock);
        skuStockMapper.updateByPrimaryKeySelective(restore);
    }

    @Test
    public void shouldFixBothDirectionsAndIgnoreConsistentValue() {
        // ① 缓存偏高（模拟归还成功但数据库没减，或后台把库存调低了）
        stringRedisTemplate.opsForValue()
                .set(SkuStockCache.cacheKey(SKU_ID), "999", Duration.ofMinutes(30));
        StockReconcileTask.ReconcileResult high = reconcileTask.reconcile();
        assertEquals(STOCK, skuStockCache.getCachedStock(SKU_ID), "缓存偏高应被压回数据库值");
        assertTrue(high.fixed() >= 1, "本次应至少修正 1 个偏差");
        System.out.println("偏高修正通过：999 → " + skuStockCache.getCachedStock(SKU_ID));

        // ② 缓存偏低（模拟归还失败，库存被吐掉）
        stringRedisTemplate.opsForValue()
                .set(SkuStockCache.cacheKey(SKU_ID), "1", Duration.ofMinutes(30));
        StockReconcileTask.ReconcileResult low = reconcileTask.reconcile();
        assertEquals(STOCK, skuStockCache.getCachedStock(SKU_ID), "缓存偏低应被拉回数据库值");
        assertTrue(low.fixed() >= 1, "本次应至少修正 1 个偏差");
        System.out.println("偏低修正通过：1 → " + skuStockCache.getCachedStock(SKU_ID));

        // ③ 已一致，重复对账不应再改
        StockReconcileTask.ReconcileResult consistent = reconcileTask.reconcile();
        assertEquals(0, consistent.fixed(), "值一致时不应产生修正");
        System.out.println("幂等验证通过：一致状态下修正数 = " + consistent.fixed());
    }

    @Test
    public void shouldEvictCacheWhenSkuNotExists() {
        // 造一个数据库中不存在的 SKU 缓存
        stringRedisTemplate.opsForValue()
                .set(SkuStockCache.cacheKey(GHOST_SKU_ID), "5", Duration.ofMinutes(30));

        StockReconcileTask.ReconcileResult result = reconcileTask.reconcile();

        assertNull(skuStockCache.getCachedStock(GHOST_SKU_ID), "不存在的 SKU 缓存应被清理");
        assertTrue(result.evicted() >= 1, "本次应至少清理 1 个失效缓存");
        System.out.println("失效清理通过：SKU " + GHOST_SKU_ID + " 的缓存已删除");
    }
}
