package com.macro.mall.portal;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 消息重试与失败转移的配置校验。
 *
 * <p>本测试的价值在于：只要 {@code RabbitMqConfig} 里的
 * {@code RetryInterceptorBuilder} / {@code RepublishMessageRecoverer} 配错，
 * Spring 上下文就起不来，测试会直接失败。所以「能启动」本身就是一次有效校验。
 *
 * <p>完整的失败转移链路（消费失败 → 重试 3 次 → 转投兜底队列）需要观察运行日志，
 * 由手工验证完成，见项目文档。
 */
@SpringBootTest
public class CancelOrderRetryConfigTest {

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Test
    public void errorQueueShouldBeDeclared() {
        Properties queueProperties = amqpAdmin.getQueueProperties("mall.order.cancel.error");
        assertNotNull(queueProperties, "兜底队列 mall.order.cancel.error 应被声明");

        System.out.println("兜底队列已声明：" + queueProperties);

        // 三个队列都应存在
        assertNotNull(amqpAdmin.getQueueProperties("mall.order.cancel"), "消费队列应存在");
        assertNotNull(amqpAdmin.getQueueProperties("mall.order.cancel.ttl"), "延迟队列应存在");
    }
}
