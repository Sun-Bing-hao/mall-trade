package com.macro.mall.portal;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 支付回调的状态机校验验证。
 *
 * <p><b>修复前的漏洞</b>：{@code paySuccess} 直接用
 * {@code updateByPrimaryKeySelective} 把订单改成「已支付」，没有状态条件。
 * 一笔已经被超时取消（status=4）的订单收到迟到的支付回调时，会被「复活」成已支付，
 * 同时真实库存被扣掉、{@code lock_stock} 被减成负数。
 *
 * <p><b>修复后</b>：改为 {@code UPDATE ... WHERE status = 0} 并判断影响行数，
 * 状态不对直接返回 0，不执行任何库存操作。
 *
 * <p>本测试覆盖两个场景：
 * <ul>
 *   <li>已取消的订单收到支付回调 → 拒绝，订单和库存都不变；</li>
 *   <li>正常待付款订单收到支付回调 → 成功，状态流转且库存正确扣减。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.druid.max-active=60",
        "spring.data.redis.timeout=2000ms"
})
public class PaySuccessStateMachineTest {

    private static final Long SKU_ID = 110L;
    private static final Long MEMBER_ID = 1L;
    private static final int ORDER_STATUS_PENDING = 0;
    private static final int ORDER_STATUS_PAID = 1;
    private static final int ORDER_STATUS_CLOSED = 4;

    @Autowired
    private OmsPortalOrderService portalOrderService;

    @Autowired
    private OmsOrderMapper orderMapper;

    @Autowired
    private OmsOrderItemMapper orderItemMapper;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

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
        order.setOrderSn("TESTPAY" + System.currentTimeMillis());
        order.setMemberId(MEMBER_ID);
        order.setReceiverName("测试收货人");
        order.setReceiverPhone("13800000000");
        order.setStatus(ORDER_STATUS_PENDING);
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

        // 模拟下单后的状态：锁定 1 件
        PmsSkuStock locked = new PmsSkuStock();
        locked.setId(SKU_ID);
        locked.setLockStock(originLockStock + 1);
        skuStockMapper.updateByPrimaryKeySelective(locked);

        System.out.println("准备完成：订单 id=" + testOrderId + "，stock=" + originStock
                + "，lock_stock=" + (originLockStock + 1));
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
        System.out.println("已清理测试订单 " + testOrderId + " 并恢复库存");
    }

    /**
     * 核心场景：订单已被取消，迟到的支付回调必须被拒绝。
     * 修复前这里会让订单「复活」、库存变负。
     */
    @Test
    public void payCallbackShouldBeRejectedWhenOrderCancelled() {
        // 1. 先取消订单（模拟超时取消），库存释放
        portalOrderService.cancelOrder(testOrderId);
        OmsOrder afterCancel = orderMapper.selectByPrimaryKey(testOrderId);
        PmsSkuStock afterCancelSku = skuStockMapper.selectByPrimaryKey(SKU_ID);

        // 2. 再模拟支付回调到达
        Integer result = portalOrderService.paySuccess(testOrderId, 1);

        OmsOrder afterPay = orderMapper.selectByPrimaryKey(testOrderId);
        PmsSkuStock afterPaySku = skuStockMapper.selectByPrimaryKey(SKU_ID);

        System.out.println();
        System.out.println("========== 已取消订单收到支付回调 ==========");
        System.out.println("  取消后状态 / 锁定量 : " + afterCancel.getStatus() + " / " + afterCancelSku.getLockStock());
        System.out.println("  支付回调返回值      : " + result + "（期望 0，表示拒绝处理）");
        System.out.println("  回调后状态          : " + afterPay.getStatus() + "（期望 4，不能被复活）");
        System.out.println("  回调后 stock        : " + afterPaySku.getStock() + "（期望 " + originStock + "，不能扣）");
        System.out.println("  回调后 lock_stock   : " + afterPaySku.getLockStock() + "（期望 0，不能变负）");
        System.out.println("------------------------------------------");
        System.out.println();

        assertEquals(ORDER_STATUS_CLOSED, afterPay.getStatus(), "已取消的订单不应被支付回调复活");
        assertEquals(0, result, "回调应被拒绝并返回 0");
        assertEquals(originStock, afterPaySku.getStock(), "真实库存不应被扣减");
        assertEquals(originLockStock, afterPaySku.getLockStock(), "锁定量不应变成负数");
    }

    /** 正常场景：待付款订单收到支付回调，状态流转 + 库存扣减都要正确 */
    @Test
    public void payCallbackShouldSucceedForPendingOrder() {
        Integer result = portalOrderService.paySuccess(testOrderId, 1);

        OmsOrder afterPay = orderMapper.selectByPrimaryKey(testOrderId);
        PmsSkuStock afterPaySku = skuStockMapper.selectByPrimaryKey(SKU_ID);

        System.out.println("正常支付验证：状态 " + afterPay.getStatus() + "，stock " + afterPaySku.getStock()
                + "，lock_stock " + afterPaySku.getLockStock() + "，返回值 " + result);

        assertEquals(ORDER_STATUS_PAID, afterPay.getStatus(), "订单应变为已支付");
        assertEquals(originStock - 1, afterPaySku.getStock(), "真实库存应扣减 1 件");
        assertEquals(originLockStock, afterPaySku.getLockStock(), "锁定量应退回原值");
    }

    /** 重复回调：支付平台重试时只应成功一次，第二次不能重复扣库存 */
    @Test
    public void duplicatePayCallbackShouldBeIdempotent() {
        portalOrderService.paySuccess(testOrderId, 1);
        int stockAfterFirst = skuStockMapper.selectByPrimaryKey(SKU_ID).getStock();

        Integer second = portalOrderService.paySuccess(testOrderId, 1);
        int stockAfterSecond = skuStockMapper.selectByPrimaryKey(SKU_ID).getStock();

        System.out.println("重复回调验证：第二次返回 " + second + "，stock " + stockAfterFirst
                + " → " + stockAfterSecond);

        assertEquals(0, second, "重复回调应被拒绝");
        assertEquals(stockAfterFirst, stockAfterSecond, "重复回调不应再次扣减库存");
    }
}
