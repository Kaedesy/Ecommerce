package com.macro.mall.portal.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run only against the dedicated validation-mall Redis container. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class ProductDetailCacheIntegrationTest {
    @Test
    void twoInstancesShareL2AndBothInvalidateTheirLocalEntries() throws Exception {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("localhost", 16379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper();
        ProductDetailCache firstInstance = new ProductDetailCache(redis, objectMapper);
        ProductDetailCache secondInstance = new ProductDetailCache(redis, objectMapper);
        RedisMessageListenerContainer listeners = new RedisMessageListenerContainer();
        long productId = System.nanoTime() & Long.MAX_VALUE;
        String sharedKey = "mall:product:detail:" + productId;

        try {
            listeners.setConnectionFactory(connectionFactory);
            listeners.addMessageListener((message, pattern) -> firstInstance.onMessage(message, pattern),
                    new ChannelTopic(ProductDetailCache.INVALIDATION_CHANNEL));
            listeners.addMessageListener((message, pattern) -> secondInstance.onMessage(message, pattern),
                    new ChannelTopic(ProductDetailCache.INVALIDATION_CHANNEL));
            listeners.afterPropertiesSet();
            listeners.start();

            long subscriptionDeadline = System.currentTimeMillis() + 3000;
            Long subscribers = 0L;
            while (subscribers < 2 && System.currentTimeMillis() < subscriptionDeadline) {
                subscribers = redis.convertAndSend(ProductDetailCache.INVALIDATION_CHANNEL, "-1");
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertTrue(subscribers > 0, "the Redis listener container should subscribe to the invalidation channel");

            PmsPortalProductDetail firstValue = firstInstance.get(productId, PmsPortalProductDetail::new);
            assertNotNull(firstValue);
            PmsPortalProductDetail secondValue = secondInstance.get(productId, PmsPortalProductDetail::new);
            assertNotNull(secondValue);
            assertNotSame(firstValue, secondValue, "the second instance should deserialize the Redis L2 value");
            assertNotNull(redis.opsForValue().get(sharedKey));

            Long delivered = redis.convertAndSend(ProductDetailCache.INVALIDATION_CHANNEL, Long.toString(productId));
            assertTrue(delivered > 0, "Redis Pub/Sub should deliver the invalidation event");
            long invalidationDeadline = System.currentTimeMillis() + 3000;
            while (redis.opsForValue().get(sharedKey) != null && System.currentTimeMillis() < invalidationDeadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertEquals(null, redis.opsForValue().get(sharedKey), "Pub/Sub should delete the shared cache entry");

            PmsPortalProductDetail newSecondValue = secondInstance.get(productId, PmsPortalProductDetail::new);
            assertNotSame(secondValue, newSecondValue, "Pub/Sub should evict the second instance's L1 entry");
            PmsPortalProductDetail newFirstValue = firstInstance.get(productId, PmsPortalProductDetail::new);
            assertNotSame(firstValue, newFirstValue, "Pub/Sub should evict the first instance's L1 entry");
        } finally {
            redis.delete(sharedKey);
            listeners.stop();
            listeners.destroy();
            connectionFactory.destroy();
        }
    }
}