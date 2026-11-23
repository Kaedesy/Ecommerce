package com.macro.mall.portal.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlashPromotionRabbitConfig {
    public static final String EXCHANGE = "mall.flash.exchange";
    public static final String ROUTING_KEY = "mall.flash.order.create";
    public static final String QUEUE = "mall.flash.order.create";
    public static final String DEAD_EXCHANGE = "mall.flash.dlx";
    public static final String DEAD_QUEUE = "mall.flash.order.dlq";

    @Bean
    DirectExchange flashOrderExchange() { return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build(); }
    @Bean
    DirectExchange flashDeadExchange() { return ExchangeBuilder.directExchange(DEAD_EXCHANGE).durable(true).build(); }
    @Bean
    Queue flashOrderQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_QUEUE).build();
    }
    @Bean
    Queue flashDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }
    @Bean
    Binding flashOrderBinding(Queue flashOrderQueue, DirectExchange flashOrderExchange) {
        return BindingBuilder.bind(flashOrderQueue).to(flashOrderExchange).with(ROUTING_KEY);
    }
    @Bean
    Binding flashDeadBinding(Queue flashDeadQueue, DirectExchange flashDeadExchange) {
        return BindingBuilder.bind(flashDeadQueue).to(flashDeadExchange).with(DEAD_QUEUE);
    }
    @Bean
    SimpleRabbitListenerContainerFactory flashListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(500, 2.0, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}