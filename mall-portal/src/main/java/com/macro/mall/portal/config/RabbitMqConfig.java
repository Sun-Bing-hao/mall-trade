package com.macro.mall.portal.config;

import com.macro.mall.portal.domain.QueueEnum;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列相关配置
 * Created by macro on 2018/9/14.
 */
@Configuration
public class RabbitMqConfig {

    /** 消费失败的最大尝试次数（含首次消费） */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * 订单消息实际消费队列所绑定的交换机
     */
    @Bean
    DirectExchange orderDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单延迟队列队列所绑定的交换机
     */
    @Bean
    DirectExchange orderTtlDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单实际消费队列
     */
    @Bean
    public Queue orderQueue() {
        return new Queue(QueueEnum.QUEUE_ORDER_CANCEL.getName());
    }

    /**
     * 订单延迟队列（死信队列）
     */
    @Bean
    public Queue orderTtlQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getName())
                .withArgument("x-dead-letter-exchange", QueueEnum.QUEUE_ORDER_CANCEL.getExchange())//到期后转发的交换机
                .withArgument("x-dead-letter-routing-key", QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey())//到期后转发的路由键
                .build();
    }

    /**
     * 将订单队列绑定到交换机
     */
    @Bean
    Binding orderBinding(DirectExchange orderDirect,Queue orderQueue){
        return BindingBuilder
                .bind(orderQueue)
                .to(orderDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey());
    }

    /**
     * 将订单延迟队列绑定到交换机
     */
    @Bean
    Binding orderTtlBinding(DirectExchange orderTtlDirect,Queue orderTtlQueue){
        return BindingBuilder
                .bind(orderTtlQueue)
                .to(orderTtlDirect)
                .with(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getRouteKey());
    }

    // ==================== 消费失败兜底 ====================

    /** 取消失败消息的兜底交换机 */
    @Bean
    DirectExchange orderErrorDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL_ERROR.getExchange())
                .durable(true)
                .build();
    }

    /** 取消失败消息的兜底队列 */
    @Bean
    public Queue orderErrorQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_ORDER_CANCEL_ERROR.getName())
                .build();
    }

    @Bean
    Binding orderErrorBinding(DirectExchange orderErrorDirect, Queue orderErrorQueue) {
        return BindingBuilder
                .bind(orderErrorQueue)
                .to(orderErrorDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL_ERROR.getRouteKey());
    }

    /**
     * 监听容器工厂：为重试与失败转移提供支撑。
     *
     * <p>三个关键设置：
     * <ol>
     *   <li><b>重试 3 次</b>（间隔 1s、2s、4s，上限 5s）：数据库抖动、连接超时这类临时故障，
     *       重试往往就能成功，不需要人工介入；</li>
     *   <li><b>重试耗尽后转投错误队列</b>（{@link RepublishMessageRecoverer}）：
     *       消息带着 {@code x-exception-message} 等原始异常信息一起转移，便于事后定位；</li>
     *   <li><b>defaultRequeueRejected = false</b>：这一步最容易漏。Spring 默认为 true，
     *       重试耗尽后会把消息 nack 回队列，导致同一条消息被无限重投，
     *       并且占满消费者线程，把后续正常订单全部堵死。</li>
     * </ol>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 先应用 yml 中的 listener.simple 配置（并发数、预取数量等），再覆盖重试相关设置
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(rabbitTemplate));
        return factory;
    }

    private Advice retryInterceptor(RabbitTemplate rabbitTemplate) {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(MAX_RETRY_ATTEMPTS)
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(new RepublishMessageRecoverer(
                        rabbitTemplate,
                        QueueEnum.QUEUE_ORDER_CANCEL_ERROR.getExchange(),
                        QueueEnum.QUEUE_ORDER_CANCEL_ERROR.getRouteKey()))
                .build();
    }

}
