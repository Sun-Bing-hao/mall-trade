package com.macro.mall.portal;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.component.SkuStockCache;
import com.macro.mall.portal.dao.PortalOrderDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 预扣 + 数据库原子锁定 的联合并发验证。
 *
 * <p>模拟真实的 {@code lockStock()} 两级流程：
 * <pre>
 *   if (Redis 预扣成功) {
 *       数据库原子锁定;
 *       成功计数++;
 *   } else {
 *       售罄计数++;
 *   }
 * </pre>
 *
 * <p>场景：库存 100，200 个线程各买 1 件。期望结果：
 * <ul>
 *   <li>Redis 预扣成功 100 次，拒绝 100 次 —— 说明流量被挡在数据库之外</li>
 *   <li>数据库 lock_stock 精确等于 100 —— 说明最终没有超卖</li>
 *   <li>Redis 剩余可售量为 0</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.druid.max-active=60",
        // 生产配置是 300ms，本机 Docker + 高并发下容易出现命令排队超时，
        // 测试放大到 2s，避免把「客户端超时」误判成「预扣逻辑有问题」
        "spring.data.redis.timeout=2000ms"
})
public class StockRedisDeductTest {

    private static final Long SKU_ID = 110L;
    private static final int THREADS = 200;
    private static final int STOCK = 100;

    @Autowired
    private SkuStockCache skuStockCache;

    @Autowired
    private PortalOrderDao portalOrderDao;

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

        // 清空缓存，让本次测试从数据库重新加载，保证起点一致
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        // 预热缓存：正式环境下商品上架/详情页访问时就应该完成，这里显式做一次，
        // 让测试聚焦于「预扣是否原子」，而不是「缓存击穿如何处理」
        skuStockCache.initStock(SKU_ID);
        System.out.println("准备完成：SKU " + SKU_ID + " stock=" + STOCK + ", lock_stock=0, 缓存预热为 "
                + skuStockCache.getCachedStock(SKU_ID));
    }

    @AfterEach
    public void restore() {
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        PmsSkuStock restore = new PmsSkuStock();
        restore.setId(SKU_ID);
        restore.setStock(originStock);
        restore.setLockStock(originLockStock);
        skuStockMapper.updateByPrimaryKeySelective(restore);
        System.out.println("已恢复：stock=" + originStock + ", lock_stock=" + originLockStock + "，缓存已清理");
    }

    @Test
    public void redisDeductAndDbLockUnderConcurrency() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREADS);
        AtomicInteger deducted = new AtomicInteger();
        AtomicInteger rejectedByRedis = new AtomicInteger();
        AtomicInteger rejectedByDb = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 第一级：Redis 预扣
                    if (!skuStockCache.tryDeduct(SKU_ID, 1)) {
                        rejectedByRedis.incrementAndGet();
                        return;
                    }
                    deducted.incrementAndGet();
                    // 第二级：数据库原子锁定
                    if (portalOrderDao.lockSkuStock(SKU_ID, 1) == 0) {
                        rejectedByDb.incrementAndGet();
                        skuStockCache.rollback(SKU_ID, 1);
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        long begin = System.currentTimeMillis();
        startGate.countDown();
        endGate.await(180, TimeUnit.SECONDS);
        long cost = System.currentTimeMillis() - begin;
        pool.shutdown();

        PmsSkuStock after = skuStockMapper.selectByPrimaryKey(SKU_ID);
        Integer redisLeft = skuStockCache.getCachedStock(SKU_ID);
        double tps = cost > 0 ? THREADS * 1000.0 / cost : THREADS;

        System.out.println();
        System.out.println("========== Redis预扣 + 数据库锁定 联合并发测试 ==========");
        System.out.println("  并发线程数           : " + THREADS);
        System.out.println("  初始库存             : " + STOCK);
        System.out.println("  Redis 预扣成功       : " + deducted.get());
        System.out.println("  Redis 层拒绝         : " + rejectedByRedis.get());
        System.out.println("  数据库层拒绝         : " + rejectedByDb.get());
        System.out.println("  异常数               : " + error.get());
        System.out.println("  总耗时               : " + cost + " ms");
        System.out.println("  吞吐量               : " + String.format("%.1f", tps) + " 次/秒");
        System.out.println("  数据库 lock_stock    : " + after.getLockStock());
        System.out.println("  Redis 剩余可售       : " + redisLeft);
        System.out.println("--------------------------------------------------------");
        System.out.println("  进入数据库的请求     : " + deducted.get() + " 次（" + THREADS
                + " 个请求中 " + rejectedByRedis.get() + " 个被 Redis 拦截）");
        System.out.println("========================================================");
        System.out.println();

        // 同时落一份到文件，避免控制台输出被日志淹没，方便收集改造前后的对比数据
        String summary = String.format(
                "并发=%d 库存=%d 预扣成功=%d Redis拦截=%d DB拒绝=%d 异常=%d 耗时=%dms 吞吐=%.1f/s DB锁定量=%d Redis剩余=%d",
                THREADS, STOCK, deducted.get(), rejectedByRedis.get(), rejectedByDb.get(), error.get(),
                cost, tps, after.getLockStock(), redisLeft);
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target", "stock-redis-benchmark.txt"),
                    summary, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写文件失败不影响测试结论
        }

        assertEquals(0, error.get(), "不应出现异常");
        assertEquals(STOCK, deducted.get(), "Redis 预扣成功数应等于库存量");
        assertEquals(THREADS, deducted.get() + rejectedByRedis.get(), "预扣成功与拒绝之和应等于总请求数");
        assertEquals(0, rejectedByDb.get(), "Redis 预扣成功后，数据库不应再拒绝");
        assertEquals(STOCK, after.getLockStock(), "数据库锁定量应精确等于库存量");
        assertEquals(0, redisLeft, "Redis 剩余可售量应为 0");
        assertTrue(after.getLockStock() <= after.getStock(), "不允许超卖");
    }

    /**
     * 验证归还逻辑：预扣后归还，缓存值必须回到原值。
     * 这是订单取消、下单失败补偿的基础。
     */
    @Test
    public void rollbackShouldRestoreCachedStock() {
        skuStockCache.initStock(SKU_ID);
        Integer before = skuStockCache.getCachedStock(SKU_ID);
        assertEquals(STOCK, before, "初始化后缓存应等于可售库存");

        assertTrue(skuStockCache.tryDeduct(SKU_ID, 10), "预扣 10 件应成功");
        assertEquals(before - 10, skuStockCache.getCachedStock(SKU_ID), "预扣后可售量应减少 10");

        skuStockCache.rollback(SKU_ID, 10);
        assertEquals(before, skuStockCache.getCachedStock(SKU_ID), "归还后可售量应恢复");

        System.out.println("归还验证通过：预扣 10 件后归还，缓存值 " + before + " 保持不变");
    }
}
