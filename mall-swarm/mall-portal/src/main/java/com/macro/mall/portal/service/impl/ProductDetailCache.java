package com.macro.mall.portal.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Two-level cache for product details with bounded Redis-lock cache rebuilding. */
@Component
public class ProductDetailCache implements MessageListener {
    public static final String INVALIDATION_CHANNEL = "mall:product:cache:invalidate";
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductDetailCache.class);
    private static final String KEY_PREFIX = "mall:product:detail:";
    private static final String LOCK_PREFIX = "mall:product:detail:lock:";
    private static final String NULL_SENTINEL = "__MALL_PRODUCT_NOT_FOUND__";
    private static final Duration LOCAL_TTL = Duration.ofSeconds(60);
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);
    private static final Duration NULL_TTL = Duration.ofSeconds(20);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final Cache<Long, PmsPortalProductDetail> localCache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(LOCAL_TTL).build();
    private final Cache<Long, Boolean> localMissCache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(NULL_TTL).build();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductDetailCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public PmsPortalProductDetail get(Long productId, Supplier<PmsPortalProductDetail> loader) {
        if (productId == null) return loader.get();
        PmsPortalProductDetail local = localCache.getIfPresent(productId);
        if (local != null) return local;
        if (Boolean.TRUE.equals(localMissCache.getIfPresent(productId))) return null;

        PmsPortalProductDetail shared = readShared(productId);
        if (shared != null || Boolean.TRUE.equals(localMissCache.getIfPresent(productId))) return shared;

        String lockKey = LOCK_PREFIX + productId;
        String token = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL));
        } catch (Exception exception) {
            LOGGER.warn("Redis cache rebuild lock unavailable for product {}; falling back to database", productId, exception);
        }
        if (locked) {
            try {
                shared = readShared(productId);
                if (shared != null || Boolean.TRUE.equals(localMissCache.getIfPresent(productId))) return shared;
                return loadAndCache(productId, loader, true);
            } finally {
                try {
                    redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), token);
                } catch (Exception exception) {
                    LOGGER.debug("Could not release product cache lock for {}", productId, exception);
                }
            }
        }

        // Wait briefly for the lock owner to populate L2; never block a request indefinitely.
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                TimeUnit.MILLISECONDS.sleep(30);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
            shared = readShared(productId);
            if (shared != null || Boolean.TRUE.equals(localMissCache.getIfPresent(productId))) return shared;
        }
        return loadAndCache(productId, loader, false);
    }

    private PmsPortalProductDetail readShared(Long productId) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + productId);
            if (value == null) return null;
            if (NULL_SENTINEL.equals(value)) {
                localMissCache.put(productId, true);
                return null;
            }
            PmsPortalProductDetail detail = objectMapper.readValue(value, PmsPortalProductDetail.class);
            if (detail != null) localCache.put(productId, detail);
            return detail;
        } catch (Exception exception) {
            LOGGER.warn("Could not read product detail cache for {}; falling back", productId, exception);
            return null;
        }
    }

    private PmsPortalProductDetail loadAndCache(Long productId, Supplier<PmsPortalProductDetail> loader,
                                                boolean writeShared) {
        PmsPortalProductDetail detail = loader.get();
        if (detail == null) {
            localMissCache.put(productId, true);
            if (writeShared) writeShared(productId, NULL_SENTINEL, NULL_TTL);
            return null;
        }
        localCache.put(productId, detail);
        if (writeShared) {
            try {
                writeShared(productId, objectMapper.writeValueAsString(detail), REDIS_TTL);
            } catch (Exception exception) {
                LOGGER.warn("Could not serialize product detail cache for {}", productId, exception);
            }
        }
        return detail;
    }

    private void writeShared(Long productId, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + productId, value, ttl);
        } catch (Exception exception) {
            LOGGER.warn("Could not write product detail cache for {}", productId, exception);
        }
    }

    public void invalidate(Long productId) {
        if (productId == null) return;
        localCache.invalidate(productId);
        localMissCache.invalidate(productId);
        try {
            redisTemplate.delete(KEY_PREFIX + productId);
        } catch (Exception exception) {
            LOGGER.warn("Could not invalidate shared product detail cache for {}", productId, exception);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Long productId = Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
            invalidate(productId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Ignoring invalid product cache invalidation message", exception);
        }
    }
}
