package com.macro.mall.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Publishes committed product changes to search and cache consumers. */
@Component
@ConditionalOnProperty(name = "mall.product-outbox.enabled", havingValue = "true", matchIfMissing = true)
public class ProductOutboxPublisher {
    public static final String EXCHANGE = "mall.product.changed";
    public static final String ROUTING_KEY = "product.changed";
    public static final String CACHE_INVALIDATION_CHANNEL = "mall:product:cache:invalidate";
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductOutboxPublisher.class);
    private static final int BATCH_SIZE = 30;
    private static final int MAX_ATTEMPTS = 12;
    private static final int LEASE_SECONDS = 60;

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductOutboxPublisher(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate,
                                  StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${mall.product-outbox.poll-interval-ms:500}")
    public void publishDueEvents() {
        jdbcTemplate.update("UPDATE pms_product_outbox_event SET status='DEAD', locked_until=NULL, " +
                        "last_error=COALESCE(last_error,'Publisher lease expired at retry limit'), update_time=NOW() " +
                        "WHERE status='PROCESSING' AND locked_until<=NOW() AND attempts>=?", MAX_ATTEMPTS);
        List<Long> eventIds = jdbcTemplate.queryForList(
                "SELECT id FROM pms_product_outbox_event " +
                        "WHERE ((status IN ('PENDING','RETRY') AND next_attempt_time<=NOW()) " +
                        "OR (status='PROCESSING' AND locked_until<=NOW())) AND attempts < ? " +
                        "ORDER BY id LIMIT ?", Long.class, MAX_ATTEMPTS, BATCH_SIZE);
        for (Long eventId : eventIds) {
            if (claim(eventId) != 1) continue;
            publishClaimed(eventId);
        }
    }

    private int claim(Long eventId) {
        return jdbcTemplate.update(
                "UPDATE pms_product_outbox_event SET status='PROCESSING', attempts=attempts+1, " +
                        "locked_until=DATE_ADD(NOW(), INTERVAL ? SECOND), update_time=NOW() " +
                        "WHERE id=? AND (((status IN ('PENDING','RETRY')) AND next_attempt_time<=NOW()) " +
                        "OR (status='PROCESSING' AND locked_until<=NOW())) AND attempts < ?",
                LEASE_SECONDS, eventId, MAX_ATTEMPTS);
    }

    private void publishClaimed(Long eventId) {
        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT id, product_id FROM pms_product_outbox_event WHERE id=? AND status='PROCESSING'", eventId);
        long productId = ((Number) event.get("product_id")).longValue();
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId, "productId", productId));
            CorrelationData correlation = new CorrelationData(eventId.toString());
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, payload, message -> {
                message.getMessageProperties().setMessageId(eventId.toString());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Product event was not routed: " + confirm.getReason());
            }

            // Redis Pub/Sub invalidates each portal instance's local Caffeine entry.
            // If this fails after RabbitMQ confirms, retrying is safe: both consumers are idempotent.
            redisTemplate.convertAndSend(CACHE_INVALIDATION_CHANNEL, Long.toString(productId));
            int updated = jdbcTemplate.update(
                    "UPDATE pms_product_outbox_event SET status='PUBLISHED', locked_until=NULL, " +
                            "last_error=NULL, update_time=NOW() WHERE id=? AND status='PROCESSING'", eventId);
            if (updated != 1) LOGGER.warn("Product Outbox {} confirmed but state was not marked published", eventId);
        } catch (Exception exception) {
            int attempts = jdbcTemplate.queryForObject(
                    "SELECT attempts FROM pms_product_outbox_event WHERE id=?", Integer.class, eventId);
            int retrySeconds = (int) Math.min(300L, 1L << Math.min(Math.max(attempts - 1, 0), 8));
            String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            if (error.length() > 1000) error = error.substring(0, 1000);
            jdbcTemplate.update(
                    "UPDATE pms_product_outbox_event SET status=CASE WHEN attempts>=? THEN 'DEAD' ELSE 'RETRY' END, " +
                            "next_attempt_time=DATE_ADD(NOW(), INTERVAL ? SECOND), locked_until=NULL, " +
                            "last_error=?, update_time=NOW() WHERE id=? AND status='PROCESSING'",
                    MAX_ATTEMPTS, retrySeconds, error, eventId);
            LOGGER.error("Product Outbox {} delivery failed; attempts={}", eventId, attempts, exception);
        }
    }
}
