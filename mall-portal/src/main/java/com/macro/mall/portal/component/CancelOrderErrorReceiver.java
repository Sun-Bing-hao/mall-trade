package com.macro.mall.portal.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 订单取消失败消息的兜底消费者。
 *
 * <p>主队列 {@code mall.order.cancel} 的消息在重试耗尽后，会被
 * {@code RepublishMessageRecoverer} 转投到 {@code mall.order.cancel.error}（见 RabbitMqConfig），
 * 由这里接管。这样做的目的有两个：
 * <ul>
 *   <li>异常消息不再无限重投，避免占满消费者线程、堵死后续正常订单；</li>
 *   <li>失败信息被保留下来（{@code x-exception-message}、{@code x-exception-stacktrace}），可追溯。</li>
 * </ul>
 *
 * <p><b>本类绝对不能抛异常</b>：一旦抛出，容器会再次走重试 + 转移逻辑，
 * 消息会在错误队列里自己循环。所以消息体解析也放在 try-catch 里。
 *
 * <p>局限：当前只记录日志，消息消费后即从队列消失。生产环境应落库到失败订单表，
 * 由定时任务重试或人工处理，否则日志被清理后就彻底丢失了线索。
 */
@Slf4j
@Component
public class CancelOrderErrorReceiver {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "mall.order.cancel.error")
    public void handle(Message message) {
        String orderId;
        try {
            Object body = rabbitTemplate.getMessageConverter().fromMessage(message);
            orderId = String.valueOf(body);
        } catch (Exception e) {
            orderId = "<消息体解析失败>";
        }
        String errorMessage = (String) message.getMessageProperties().getHeader("x-exception-message");
        log.error("订单取消失败，已转入兜底队列，需人工介入。orderId={}, 异常={}", orderId, errorMessage);
    }
}
