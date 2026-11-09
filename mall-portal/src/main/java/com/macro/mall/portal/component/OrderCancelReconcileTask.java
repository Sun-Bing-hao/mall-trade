package com.macro.mall.portal.component;

import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.service.OmsPortalOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单取消的兜底扫描任务（最后一道防线）。
 *
 * <p>前面的机制都是「消息驱动」的：下单时发一条延迟消息，到期后由消费者调用
 * {@code cancelOrder}。但消息本身可能不可靠：
 * <ul>
 *   <li>消费失败重试耗尽后转入兜底队列，只留下一条日志，订单从此无人处理；</li>
 *   <li>Broker 未持久化或数据丢失，消息直接没了；</li>
 *   <li>发送时 Broker 短暂不可用，消息没进队列（下单事务会回滚，但已入队的场景仍可能出问题）。</li>
 * </ul>
 *
 * <p>本任务不依赖任何消息，直接查数据库：把「状态仍是待付款、且创建时间已超过
 * 超时时间 + 宽限期」的订单捞出来，逐笔调用 {@code cancelOrder}。
 *
 * <p>三个设计要点：
 * <ol>
 *   <li><b>宽限期</b>：阈值比延迟消息多留几分钟，让消息先有机会处理，
 *       避免两边同时操作同一笔订单（虽然有 CAS 保护，但没必要制造无效竞争）；</li>
 *   <li><b>限制批量</b>：停机三天可能积压几千单，一次全捞会长时间占用数据库连接，
 *       每批 100 条、逐批消化；</li>
 *   <li><b>逐笔隔离</b>：单笔失败只记日志，不影响同批其他订单，下一轮扫描还会捞到它。</li>
 * </ol>
 *
 * <p>幂等性由 {@code cancelOrder} 内部的状态机 CAS 保证：扫到一笔正在被消息处理的订单，
 * 也只是影响行数为 0 后直接返回，不会重复释放库存。
 *
 * <p>注意：当前实现假设单实例部署。若多节点同时运行，多个任务会扫描到相同订单，
 * 结果仍正确但存在重复尝试，可加一把 Redis 分布式锁让同一时刻只有一个节点执行。
 */
@Slf4j
@Component
public class OrderCancelReconcileTask {

    /** 是否启用兜底扫描，可在配置中关闭（例如压测时避免干扰） */
    @Value("${mall.order.cancel-scan.enabled:true}")
    private boolean enabled;

    /** 宽限期（分钟）：在配置的超时时间之上再多等这么久才兜底介入 */
    @Value("${mall.order.cancel-scan.grace-minutes:5}")
    private int graceMinutes;

    /** 单批最多处理多少笔 */
    @Value("${mall.order.cancel-scan.batch-size:100}")
    private int batchSize;

    @Autowired
    private PortalOrderDao portalOrderDao;

    @Autowired
    private OmsOrderSettingMapper orderSettingMapper;

    @Autowired
    private OmsPortalOrderService portalOrderService;

    @Scheduled(fixedDelayString = "${mall.order.cancel-scan.interval-ms:300000}", initialDelay = 60000)
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        try {
            int handled = scanAndCancel();
            if (handled > 0) {
                log.warn("订单取消兜底扫描：处理 {} 笔超时未支付订单（可能为消息丢失或消费失败）", handled);
            }
        } catch (Exception e) {
            // 定时任务一旦把异常抛出去，Spring 会取消后续调度，所以必须自己兜住
            log.error("订单取消兜底扫描执行失败", e);
        }
    }

    /**
     * 执行一次扫描并取消超时订单。
     *
     * @return 本次尝试处理的订单数量
     */
    public int scanAndCancel() {
        OmsOrderSetting setting = orderSettingMapper.selectByPrimaryKey(1L);
        if (setting == null || setting.getNormalOrderOvertime() == null) {
            log.warn("未读取到订单超时配置，跳过本轮兜底扫描");
            return 0;
        }
        int thresholdMinutes = setting.getNormalOrderOvertime() + graceMinutes;
        List<OmsOrder> orders = portalOrderDao.getTimeoutPendingOrders(thresholdMinutes, batchSize);
        if (orders.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (OmsOrder order : orders) {
            try {
                portalOrderService.cancelOrder(order.getId());
                handled++;
            } catch (Exception e) {
                log.error("兜底取消订单失败，orderId={}, orderSn={}", order.getId(), order.getOrderSn(), e);
            }
        }
        return handled;
    }
}
