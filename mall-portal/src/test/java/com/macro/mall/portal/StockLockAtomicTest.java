package com.macro.mall.portal;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.dao.PortalOrderDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存锁定并发安全测试（改造后验证）
 *
 * <p>对照 {@link StockRaceConditionTest}（改造前基线：200 并发只留下 3 个锁定量）。
 * 本测试使用改造后的原子 SQL {@code PortalOrderDao.lockSkuStock}，
 * 场景固定为「库存 100，200 个线程各买 1 件」，期望结果必须精确成立：
 * <ul>
 *   <li>成功锁定数 == 100（不能多卖，也不能少卖）</li>
 *   <li>失败数 == 100（库存耗尽后必须返回 0 行）</li>
 *   <li>最终 lock_stock == 100 == stock（没有超卖，也没有丢失锁定量）</li>
 * </ul>
 *
 * <p>测试前后会备份并恢复 SKU 110 的真实库存，不会污染数据。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 连接池默认 20，并发线程数要大于连接池才不会限制在串行，这里放大到 60
        "spring.datasource.druid.max-active=60"
})
public class StockLockAtomicTest {

    /** 测试用 SKU：华为 P20 黑色 6GB+64GB */
    private static final Long SKU_ID = 110L;

    /** 并发线程数：每个线程买 1 件 */
    private static final int THREADS = 200;

    /** 人为设置的库存量，保证「需求 200 > 供给 100」，必然产生抢购 */
    private static final int STOCK = 100;

    @Autowired
    private PortalOrderDao portalOrderDao;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    private int originStock;
    private int originLockStock;

    @BeforeEach
    public void prepare() {
        PmsSkuStock origin = skuStockMapper.selectByPrimaryKey(SKU_ID);
        originStock = origin.getStock();
        originLockStock = origin.getLockStock();

        // 造一个干净的靶子：库存 100、锁定量 0
        PmsSkuStock target = new PmsSkuStock();
        target.setId(SKU_ID);
        target.setStock(STOCK);
        target.setLockStock(0);
        skuStockMapper.updateByPrimaryKeySelective(target);
        System.out.println("准备完成：SKU " + SKU_ID + " 设为 stock=" + STOCK + ", lock_stock=0");
    }

    @AfterEach
    public void restore() {
        // 恢复测前的真实库存，避免影响后续用 Apifox 手工验证
        PmsSkuStock restore = new PmsSkuStock();
        restore.setId(SKU_ID);
        restore.setStock(originStock);
        restore.setLockStock(originLockStock);
        skuStockMapper.updateByPrimaryKeySelective(restore);
        System.out.println("已恢复：SKU " + SKU_ID + " stock=" + originStock + ", lock_stock=" + originLockStock);
    }

    @Test
    public void noOversellUnderConcurrency() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 改造后的原子锁定：返回 1 表示抢到，0 表示库存不足
                    int affected = portalOrderDao.lockSkuStock(SKU_ID, 1);
                    if (affected == 1) {
                        success.incrementAndGet();
                    } else {
                        soldOut.incrementAndGet();
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
        int locked = after.getLockStock();
        double tps = cost > 0 ? THREADS * 1000.0 / cost : THREADS;

        System.out.println();
        System.out.println("========== 库存锁定并发测试（改造后：原子 SQL） ==========");
        System.out.println("  并发线程数         : " + THREADS);
        System.out.println("  初始库存 stock     : " + STOCK);
        System.out.println("  抢购成功 / 售罄    : " + success.get() + " / " + soldOut.get());
        System.out.println("  异常数             : " + error.get());
        System.out.println("  总耗时             : " + cost + " ms");
        System.out.println("  吞吐量             : " + String.format("%.1f", tps) + " 次/秒");
        System.out.println("  期望 lock_stock    : " + STOCK + "（卖完即止）");
        System.out.println("  实际 lock_stock    : " + locked);
        System.out.println("  超卖数量           : " + Math.max(0, locked - STOCK));
        System.out.println("--------------------------------------------------------");
        if (locked == STOCK && success.get() == STOCK) {
            System.out.println("  结论：无超卖，且无锁定量丢失。供给 100 精确卖出 100，剩余 "
                    + (THREADS - success.get()) + " 个请求被正确拒绝。");
        } else {
            System.out.println("  结论：不符合预期！实际锁定 " + locked + "，成功 " + success.get());
        }
        System.out.println("========================================================");
        System.out.println();

        // ① 不能超卖：锁定量绝不能超过真实库存
        assertTrue(locked <= STOCK, "出现超卖：lock_stock=" + locked + " 超过库存 " + STOCK);
        // ② 不能少卖：库存卖完就该正好卖出 100 件
        assertEquals(STOCK, success.get(), "成功锁定数应等于库存量");
        // ③ 计数一致性：成功 + 售罄 = 总请求数
        assertEquals(THREADS, success.get() + soldOut.get(), "成功数与售罄数之和应等于总请求数");
        // ④ 不允许出现异常
        assertEquals(0, error.get(), "并发锁定不应抛出任何异常");
        // ⑤ 最终锁定量必须与售出量吻合
        assertEquals(success.get(), locked, "lock_stock 应精确等于成功锁定数");
    }
}
