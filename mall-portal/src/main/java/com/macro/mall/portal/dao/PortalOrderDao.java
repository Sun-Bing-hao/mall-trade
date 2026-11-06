package com.macro.mall.portal.dao;

import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao
 * Created by macro on 2018/9/4.
 */
public interface PortalOrderDao {
    /**
     * 获取订单及下单商品详情
     */
    OmsOrderDetail getDetail(@Param("orderId") Long orderId);

    /**
     * 原子锁定库存（下单时占用）
     *
     * <p>把「判断库存是否充足」和「增加锁定量」合并成一条 SQL，由数据库的行锁保证原子性。
     * 不再先 select 再 update，避免读-改-写之间的竞态窗口。
     *
     * @param skuId    商品SKU id
     * @param quantity 锁定数量
     * @return 影响行数；0 表示可用库存不足（stock - lock_stock &lt; quantity）或 SKU 不存在
     */
    int lockSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 修改 pms_sku_stock表的锁定库存及真实库存
     */
    int updateSkuStock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 获取超时订单
     * @param minute 超时时间（分）
     */
    List<OmsOrderDetail> getTimeOutOrders(@Param("minute") Integer minute);

    /**
     * 批量修改订单状态
     */
    int updateOrderStatus(@Param("ids") List<Long> ids,@Param("status") Integer status);

    /**
     * 仅当订单仍处于「待付款」时修改其状态（状态机 CAS）。
     *
     * <p>把「检查状态」和「修改状态」合并成一条带 WHERE 条件的 UPDATE，
     * 由数据库行锁保证原子性，用于订单取消、支付回调等「只能成功一次」的场景。
     *
     * <p>调用方根据返回值判断自己是否抢到了这次状态流转：
     * 返回 1 表示本次操作生效；返回 0 表示订单不存在、已被支付、已被取消或已删除。
     *
     * @param orderId    订单 id
     * @param status     目标状态
     * @param expectFrom 期望的当前状态（通常是 0 待付款）
     * @return 影响行数
     */
    int updateOrderStatusFrom(@Param("orderId") Long orderId,
                              @Param("status") Integer status,
                              @Param("expectFrom") Integer expectFrom);

    /**
     * 解除取消订单的库存锁定
     */
    int releaseSkuStockLock(@Param("itemList") List<OmsOrderItem> orderItemList);

}
