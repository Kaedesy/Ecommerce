package com.macro.mall.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductChangeRabbitConfig {
    @Bean
    TopicExchange productChangeExchange() {
        return new TopicExchange("mall.product.changed", true, false);
    }
}
