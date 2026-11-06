package com.macro.mall.portal;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.component.SkuStockCache;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 订单取消幂等性测试（改造后）。
 *
 * <p>改造前的 {@code cancelOrder} 是「先查 status=0，再 update」的 check-then-act：
 * 两个线程可能同时查到 status=0，各自执行一遍取消逻辑，导致库存被释放两次、
 * {@code lock_stock} 变成负数。
 *
 * <p>改造后用状态机 CAS（{@code UPDATE ... WHERE status = 0} + 判断影响行数）作为唯一入口，
 * 并发调用时只有一个请求能真正执行后续逻辑。
 *
 * <p>本测试构造一笔待付款订单，用多线程并发取消，验证：
 * <ul>
 *   <li>订单状态变成 4（已关闭）；</li>
 *   <li>{@code lock_stock} 只减少一次，不会变负；</li>
 *   <li>Redis 可售量只归还一次。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.druid.max-active=60"
})
public class CancelOrderIdempotentTest {

    private static final Long SKU_ID = 110L;
    private static final Long MEMBER_ID = 1L;
    private static final int THREADS = 10;

    @Autowired
    private OmsPortalOrderService portalOrderService;

    @Autowired
    private OmsOrderMapper orderMapper;

    @Autowired
    private OmsOrderItemMapper orderItemMapper;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Autowired
    private SkuStockCache skuStockCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long testOrderId;
    private int originStock;
    private int originLockStock;

    @BeforeEach
    public void prepare() {
        PmsSkuStock origin = skuStockMapper.selectByPrimaryKey(SKU_ID);
        originStock = origin.getStock();
        originLockStock = origin.getLockStock();

        // 造一笔待付款订单，占用 SKU 110 一件
        OmsOrder order = new OmsOrder();
        order.setOrderSn("TEST" + System.currentTimeMillis());
        order.setMemberId(MEMBER_ID);
        order.setReceiverName("测试收货人");
        order.setReceiverPhone("13800000000");
        order.setStatus(0);
        order.setDeleteStatus(0);
        order.setCreateTime(new Date());
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setPayAmount(BigDecimal.valueOf(100));
        orderMapper.insertSelective(order);
        testOrderId = order.getId();

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(testOrderId);
        item.setOrderSn(order.getOrderSn());
        item.setProductSkuId(SKU_ID);
        item.setProductQuantity(1);
        item.setProductName("测试商品");
        item.setProductPrice(BigDecimal.valueOf(100));
        orderItemMapper.insertSelective(item);

        // 模拟「下单已完成」的状态：DB 锁定 1 件，Redis 可售量已扣 1
        PmsSkuStock locked = new PmsSkuStock();
        locked.setId(SKU_ID);
        locked.setLockStock(originLockStock + 1);
        skuStockMapper.updateByPrimaryKeySelective(locked);

        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        skuStockCache.initStock(SKU_ID);

        System.out.println("准备完成：订单 id=" + testOrderId + "，lock_stock=" + (originLockStock + 1)
                + "，Redis=" + skuStockCache.getCachedStock(SKU_ID));
    }

    @AfterEach
    public void cleanup() {
        if (testOrderId != null) {
            OmsOrderItemExample itemExample = new OmsOrderItemExample();
            itemExample.createCriteria().andOrderIdEqualTo(testOrderId);
            orderItemMapper.deleteByExample(itemExample);
            orderMapper.deleteByPrimaryKey(testOrderId);
        }
        PmsSkuStock restore = new PmsSkuStock();
        restore.setId(SKU_ID);
        restore.setStock(originStock);
        restore.setLockStock(originLockStock);
        skuStockMapper.updateByPrimaryKeySelective(restore);
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        System.out.println("已清理测试订单 " + testOrderId + " 并恢复库存");
    }

    @Test
    public void concurrentCancelShouldOnlyTakeEffectOnce() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    portalOrderService.cancelOrder(testOrderId);
                } catch (Exception ignored) {
                    // 并发下个别调用抛异常不影响结论，重点是最终数据状态
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        OmsOrder after = orderMapper.selectByPrimaryKey(testOrderId);
        PmsSkuStock sku = skuStockMapper.selectByPrimaryKey(SKU_ID);
        Integer cached = skuStockCache.getCachedStock(SKU_ID);

        System.out.println();
        System.out.println("========== 订单取消幂等性（改造后） ==========");
        System.out.println("  并发取消次数       : " + THREADS);
        System.out.println("  订单状态           : " + after.getStatus() + "（期望 4 已关闭）");
        System.out.println("  锁定量变化         : " + (originLockStock + 1) + " → " + sku.getLockStock());
        System.out.println("  Redis 可售量       : " + cached + "（期望恢复到 " + originStock + "）");
        System.out.println("----------------------------------------------");
        System.out.println();

        assertEquals(4, after.getStatus(), "订单应被关闭");
        assertEquals(originLockStock, sku.getLockStock(), "库存只能被释放一次，10 次并发取消不应多减");
        assertEquals(originStock, cached, "Redis 可售量也只能归还一次");
    }

    @Test
    public void secondCancelShouldBeNoop() {
        portalOrderService.cancelOrder(testOrderId);
        int lockAfterFirst = skuStockMapper.selectByPrimaryKey(SKU_ID).getLockStock();
        Integer cacheAfterFirst = skuStockCache.getCachedStock(SKU_ID);

        // 再次取消（模拟 MQ 消息重复投递）
        portalOrderService.cancelOrder(testOrderId);

        int lockAfterSecond = skuStockMapper.selectByPrimaryKey(SKU_ID).getLockStock();
        Integer cacheAfterSecond = skuStockCache.getCachedStock(SKU_ID);

        System.out.println("重复取消验证：lock_stock " + lockAfterFirst + " → " + lockAfterSecond
                + "，Redis " + cacheAfterFirst + " → " + cacheAfterSecond);

        assertEquals(lockAfterFirst, lockAfterSecond, "重复取消不应再次释放库存");
        assertEquals(cacheAfterFirst, cacheAfterSecond, "重复取消不应再次归还缓存");
    }
}
