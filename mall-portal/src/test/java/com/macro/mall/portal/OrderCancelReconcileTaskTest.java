package com.macro.mall.portal;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.component.OrderCancelReconcileTask;
import com.macro.mall.portal.component.SkuStockCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 订单取消兜底扫描验证。
 *
 * <p>场景：模拟「延迟消息丢失，订单一直停在待付款」的情况，验证扫描任务能把它们捞出来取消。
 *
 * <p>为了保证测试不影响库里已有的待付款订单（例如初始化数据里的历史订单），
 * 测试开始前会把这些订单临时改成状态 99 藏起来，结束后再改回 0。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 关闭定时触发，避免调度器在测试过程中自己跑起来干扰数据
        "mall.order.cancel-scan.enabled=false",
        "spring.datasource.druid.max-active=60"
})
public class OrderCancelReconcileTaskTest {

    private static final Long SKU_ID = 110L;
    private static final Long MEMBER_ID = 1L;
    private static final int ORDER_STATUS_PENDING = 0;
    private static final int ORDER_STATUS_CLOSED = 4;
    /** 测试期间用来「藏起」已有待付款订单的临时状态 */
    private static final int ORDER_STATUS_HIDDEN = 99;

    @Autowired
    private OrderCancelReconcileTask reconcileTask;

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

    private final List<Long> hiddenOrderIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();
    private int originStock;
    private int originLockStock;
    private Long timeoutOrderId;
    private Long freshOrderId;

    @BeforeEach
    public void prepare() {
        // 1. 记录并「藏起」库里已有的待付款订单，避免被扫描任务误处理
        OmsOrderExample pendingExample = new OmsOrderExample();
        pendingExample.createCriteria().andStatusEqualTo(ORDER_STATUS_PENDING).andDeleteStatusEqualTo(0);
        List<OmsOrder> pendingOrders = orderMapper.selectByExample(pendingExample);
        hiddenOrderIds.addAll(pendingOrders.stream().map(OmsOrder::getId).collect(Collectors.toList()));
        if (!hiddenOrderIds.isEmpty()) {
            OmsOrder hidden = new OmsOrder();
            hidden.setStatus(ORDER_STATUS_HIDDEN);
            OmsOrderExample hideExample = new OmsOrderExample();
            hideExample.createCriteria().andIdIn(hiddenOrderIds);
            orderMapper.updateByExampleSelective(hidden, hideExample);
        }

        // 2. 备份 SKU 库存
        PmsSkuStock origin = skuStockMapper.selectByPrimaryKey(SKU_ID);
        originStock = origin.getStock();
        originLockStock = origin.getLockStock();

        // 3. 造两笔订单：一笔 3 小时前（已超时），一笔刚刚创建（未超时）
        timeoutOrderId = createPendingOrder(3);
        freshOrderId = createPendingOrder(0);

        // 4. 模拟两笔订单都已占用库存
        PmsSkuStock locked = new PmsSkuStock();
        locked.setId(SKU_ID);
        locked.setLockStock(originLockStock + 2);
        skuStockMapper.updateByPrimaryKeySelective(locked);

        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        skuStockCache.initStock(SKU_ID);

        System.out.println("准备完成：超时订单=" + timeoutOrderId + "，新订单=" + freshOrderId
                + "，lock_stock=" + (originLockStock + 2)
                + "，Redis=" + skuStockCache.getCachedStock(SKU_ID));
    }

    @AfterEach
    public void cleanup() {
        // 删除测试订单
        for (Long orderId : createdOrderIds) {
            OmsOrderItemExample itemExample = new OmsOrderItemExample();
            itemExample.createCriteria().andOrderIdEqualTo(orderId);
            orderItemMapper.deleteByExample(itemExample);
            orderMapper.deleteByPrimaryKey(orderId);
        }
        // 恢复被藏起来的待付款订单
        if (!hiddenOrderIds.isEmpty()) {
            OmsOrder restore = new OmsOrder();
            restore.setStatus(ORDER_STATUS_PENDING);
            OmsOrderExample restoreExample = new OmsOrderExample();
            restoreExample.createCriteria().andIdIn(hiddenOrderIds);
            orderMapper.updateByExampleSelective(restore, restoreExample);
        }
        // 恢复库存
        PmsSkuStock skuRestore = new PmsSkuStock();
        skuRestore.setId(SKU_ID);
        skuRestore.setStock(originStock);
        skuRestore.setLockStock(originLockStock);
        skuStockMapper.updateByPrimaryKeySelective(skuRestore);
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID));
        stringRedisTemplate.delete(SkuStockCache.cacheKey(SKU_ID) + ":loading");
        System.out.println("已清理测试订单并恢复数据");
    }

    @Test
    public void shouldCancelTimedOutOrderOnly() {
        int handled = reconcileTask.scanAndCancel();

        OmsOrder timeoutOrder = orderMapper.selectByPrimaryKey(timeoutOrderId);
        OmsOrder freshOrder = orderMapper.selectByPrimaryKey(freshOrderId);
        PmsSkuStock sku = skuStockMapper.selectByPrimaryKey(SKU_ID);
        Integer cached = skuStockCache.getCachedStock(SKU_ID);

        System.out.println();
        System.out.println("========== 兜底扫描验证 ==========");
        System.out.println("  本轮处理订单数     : " + handled);
        System.out.println("  3小时前订单状态    : " + timeoutOrder.getStatus() + "（期望 4 已关闭）");
        System.out.println("  刚创建订单状态     : " + freshOrder.getStatus() + "（期望 0 待付款）");
        System.out.println("  lock_stock         : " + sku.getLockStock() + "（期望 " + (originLockStock + 1) + "）");
        System.out.println("  Redis 可售量       : " + cached);
        System.out.println("----------------------------------");
        System.out.println();

        assertEquals(ORDER_STATUS_CLOSED, timeoutOrder.getStatus(), "超时订单应被兜底任务取消");
        assertEquals(ORDER_STATUS_PENDING, freshOrder.getStatus(), "未超时订单不应被处理");
        assertNotEquals(0, handled, "本轮应至少处理 1 笔订单");
        // 两笔订单各占 1 件，只取消了一笔，锁定量应只回到 origin+1
        assertEquals(originLockStock + 1, sku.getLockStock(), "只应释放超时订单占用的 1 件库存");
    }

    @Test
    public void secondScanShouldBeIdempotent() {
        reconcileTask.scanAndCancel();
        int lockAfterFirst = skuStockMapper.selectByPrimaryKey(SKU_ID).getLockStock();
        Integer cacheAfterFirst = skuStockCache.getCachedStock(SKU_ID);

        // 再扫一次：超时订单已是已关闭状态，不应被再次处理
        reconcileTask.scanAndCancel();

        int lockAfterSecond = skuStockMapper.selectByPrimaryKey(SKU_ID).getLockStock();
        Integer cacheAfterSecond = skuStockCache.getCachedStock(SKU_ID);

        System.out.println("重复扫描验证：lock_stock " + lockAfterFirst + " → " + lockAfterSecond
                + "，Redis " + cacheAfterFirst + " → " + cacheAfterSecond);

        assertEquals(lockAfterFirst, lockAfterSecond, "重复扫描不应再次释放库存");
        assertEquals(cacheAfterFirst, cacheAfterSecond, "重复扫描不应再次归还缓存");
    }

    /** 造一笔指定「创建时间距今多少小时」的待付款订单，并写入一条明细 */
    private Long createPendingOrder(int ageHours) {
        OmsOrder order = new OmsOrder();
        order.setOrderSn("TEST" + System.currentTimeMillis() + "-" + ageHours);
        order.setMemberId(MEMBER_ID);
        order.setReceiverName("测试收货人");
        order.setReceiverPhone("13800000000");
        order.setStatus(ORDER_STATUS_PENDING);
        order.setDeleteStatus(0);
        order.setCreateTime(new Date(System.currentTimeMillis() - ageHours * 3600_000L));
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setPayAmount(BigDecimal.valueOf(100));
        orderMapper.insertSelective(order);
        createdOrderIds.add(order.getId());

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setProductSkuId(SKU_ID);
        item.setProductQuantity(1);
        item.setProductName("测试商品");
        item.setProductPrice(BigDecimal.valueOf(100));
        orderItemMapper.insertSelective(item);

        return order.getId();
    }
}
