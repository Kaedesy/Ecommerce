package com.macro.mall.portal.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductDetailCacheTest {
    @Test
    void localCacheShouldAvoidSecondLoaderCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        ProductDetailCache cache = new ProductDetailCache(redis, new ObjectMapper());
        AtomicInteger loads = new AtomicInteger();
        PmsPortalProductDetail detail = new PmsPortalProductDetail();

        assertSame(detail, cache.get(17L, () -> { loads.incrementAndGet(); return detail; }));
        assertSame(detail, cache.get(17L, () -> { loads.incrementAndGet(); return new PmsPortalProductDetail(); }));
        assertEquals(1, loads.get());
    }

    @Test
    void negativeResultShouldBeCachedLocally() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        ProductDetailCache cache = new ProductDetailCache(redis, new ObjectMapper());
        AtomicInteger loads = new AtomicInteger();

        assertNull(cache.get(18L, () -> { loads.incrementAndGet(); return null; }));
        assertNull(cache.get(18L, () -> { loads.incrementAndGet(); return new PmsPortalProductDetail(); }));
        assertEquals(1, loads.get());
    }
}
