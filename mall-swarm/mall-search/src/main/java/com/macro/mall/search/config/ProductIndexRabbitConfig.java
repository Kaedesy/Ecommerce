package com.macro.mall.search.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductIndexRabbitConfig {
    public static final String EXCHANGE = "mall.product.changed";
    public static final String QUEUE = "mall.product.search.index";
    public static final String DEAD_EXCHANGE = "mall.product.search.dlx";
    public static final String DEAD_QUEUE = "mall.product.search.index.dlq";

    @Bean
    TopicExchange productIndexExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean
    DirectExchange productIndexDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }

    @Bean
    Queue productIndexQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_QUEUE).build();
    }

    @Bean
    Queue productIndexDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }

    @Bean
    Binding productIndexBinding(Queue productIndexQueue, TopicExchange productIndexExchange) {
        return BindingBuilder.bind(productIndexQueue).to(productIndexExchange).with("product.changed");
    }

    @Bean
    Binding productIndexDeadBinding(Queue productIndexDeadQueue, DirectExchange productIndexDeadExchange) {
        return BindingBuilder.bind(productIndexDeadQueue).to(productIndexDeadExchange).with(DEAD_QUEUE);
    }

    @Bean
    SimpleRabbitListenerContainerFactory productIndexListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(5).backOffOptions(500, 2.0, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }
}
