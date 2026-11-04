package com.macro.mall.portal;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存锁定竞态条件复现测试（改造前基线采集）
 *
 * <p>背景：OmsPortalOrderServiceImpl.lockStock() 的实现是典型的「读-改-写」：
 * <pre>
 *   L736  PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(skuId);
 *   L737  skuStock.setLockStock(skuStock.getLockStock() + quantity);
 *   L738  skuStockMapper.updateByPrimaryKeySelective(skuStock);
 * </pre>
 * 三条语句之间没有任何原子性保证。N 个线程同时执行时，会读到相同的旧值，
 * 各自加完后写回，后写的覆盖先写的 —— 锁定量被吞掉，导致「实际锁住的比卖出去的少」，
 * 也就是超卖。
 *
 * <p>本测试用 N 个线程各执行一次上述逻辑，对比「期望锁定量」与「实际锁定量」。
 * 差值就是被竞态吞掉的锁定量，即潜在的超卖数量。
 *
 * <p>注意：这是「改造前」的基线测量，因此不做断言（断言会在改造后补上），
 * 只输出数据供记录和对比。
 *
 * @see com.macro.mall.portal.service.impl.OmsPortalOrderServiceImpl#lockStock
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 连接池默认 20，并发不够就撞不出竞态窗口，这里放大到 60
        "spring.datasource.druid.max-active=60"
})
public class StockRaceConditionTest {

    /** 测试用 SKU：华为 P20 黑色 6GB+64GB，库存 487 */
    private static final Long SKU_ID = 110L;

    /** 并发线程数，每个线程锁定 1 件 */
    private static final int THREADS = 200;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Test
    public void measureLockStockRaceCondition() throws InterruptedException {
        resetLockStock();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // 起跑线：让所有线程尽量在同一时刻冲出去
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    lockStockOnce();
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
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
        int expect = THREADS;
        int actual = after.getLockStock();
        int lost = expect - actual;

        System.out.println();
        System.out.println("========== 库存锁定并发测试（改造前基线） ==========");
        System.out.println("  并发线程数         : " + THREADS);
        System.out.println("  执行成功 / 失败    : " + success.get() + " / " + failure.get());
        System.out.println("  总耗时             : " + cost + " ms");
        System.out.println("  期望 lock_stock    : " + expect);
        System.out.println("  实际 lock_stock    : " + actual);
        System.out.println("  丢失的锁定量       : " + lost);
        System.out.println("  SKU 真实库存 stock : " + after.getStock());
        System.out.println("--------------------------------------------------");
        if (lost > 0) {
            System.out.println("  结论：竞态已复现。" + success.get() + " 次加锁动作只留下 " + actual
                    + " 个锁定量，被吞掉 " + lost + " 个。");
            System.out.println("        真实库存 " + after.getStock() + " 件，但系统只认为被占用了 "
                    + actual + " 件，可售数量被高估，超卖成立。");
        } else {
            System.out.println("  结论：本次未复现（可能并发窗口太小），请重跑一次。");
        }
        System.out.println("==================================================");
        System.out.println();
    }

    /**
     * 与 OmsPortalOrderServiceImpl.lockStock() L736-738 完全等价的实现。
     * 故意保留原样，不做任何加锁 —— 要测的就是这段代码本身。
     */
    private void lockStockOnce() {
        PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(SKU_ID);
        skuStock.setLockStock(skuStock.getLockStock() + 1);
        skuStockMapper.updateByPrimaryKeySelective(skuStock);
    }

    /** 每次测试前把锁定量归零，保证可重复执行 */
    private void resetLockStock() {
        PmsSkuStock reset = new PmsSkuStock();
        reset.setId(SKU_ID);
        reset.setLockStock(0);
        skuStockMapper.updateByPrimaryKeySelective(reset);
        System.out.println("准备完成：SKU " + SKU_ID + " 的 lock_stock 已归零");
    }
}
