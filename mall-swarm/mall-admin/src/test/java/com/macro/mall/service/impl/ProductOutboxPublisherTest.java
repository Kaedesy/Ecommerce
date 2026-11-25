package com.macro.mall.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ProductOutboxPublisherTest {
    @Test
    void confirmedEventIsMarkedPublishedAndInvalidatesProductCache() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(101L));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), eq(101L))).thenReturn(Map.of("id", 101L, "product_id", 7001L));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        ProductOutboxPublisher publisher = new ProductOutboxPublisher(jdbc, rabbit, redis, new ObjectMapper());

        publisher.publishDueEvents();

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='PUBLISHED'"), any(Object[].class));
        verify(redis).convertAndSend(ProductOutboxPublisher.CACHE_INVALIDATION_CHANNEL, "7001");
    }

    @Test
    void negativePublisherConfirmSchedulesRetry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(102L));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), eq(102L))).thenReturn(Map.of("id", 102L, "product_id", 7002L));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(102L))).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker-nack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        ProductOutboxPublisher publisher = new ProductOutboxPublisher(jdbc, rabbit, redis, new ObjectMapper());

        publisher.publishDueEvents();

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status=CASE WHEN attempts>=? THEN 'DEAD' ELSE 'RETRY' END"),
                eq(12), any(), anyString(), eq(102L));
    }
}