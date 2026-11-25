package com.macro.mall.portal.config;

import com.macro.mall.portal.service.impl.ProductDetailCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ProductDetailCacheConfig {
    @Bean
    RedisMessageListenerContainer productCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory, ProductDetailCache productDetailCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(productDetailCache,
                new ChannelTopic(ProductDetailCache.INVALIDATION_CHANNEL));
        return container;
    }
}
