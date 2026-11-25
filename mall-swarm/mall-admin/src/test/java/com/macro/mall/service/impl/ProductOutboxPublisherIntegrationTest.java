package com.macro.mall.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Run only against the dedicated validation-mall MySQL and RabbitMQ containers. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class ProductOutboxPublisherIntegrationTest {
    @Test
    void committedOutboxEventIsConfirmedAndDeliveredToBoundQueue() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false",
                "root", "root");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost", 5672);
        connectionFactory.setVirtualHost("/mall");
        connectionFactory.setUsername("mall");
        connectionFactory.setPassword("mall");
        connectionFactory.setPublisherConfirmType(ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate rabbit = new RabbitTemplate(connectionFactory);
        rabbit.setMandatory(true);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        String queueName = "mall.product.changed.integration." + UUID.randomUUID();
        Long eventId = null;

        try {
            admin.declareExchange(new TopicExchange(ProductOutboxPublisher.EXCHANGE, true, false));
            admin.declareQueue(new Queue(queueName, false, false, true));
            admin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                    ProductOutboxPublisher.EXCHANGE, ProductOutboxPublisher.ROUTING_KEY, null));
            jdbc.update("INSERT INTO pms_product_outbox_event(product_id,status,attempts,next_attempt_time) " +
                    "VALUES (999999,'PENDING',0,NOW())");
            eventId = jdbc.queryForObject("SELECT id FROM pms_product_outbox_event " +
                    "WHERE product_id=999999 AND status='PENDING' ORDER BY id DESC LIMIT 1", Long.class);

            new ProductOutboxPublisher(jdbc, rabbit, redis, new ObjectMapper()).publishDueEvents();

            assertEquals("PUBLISHED", jdbc.queryForObject(
                    "SELECT status FROM pms_product_outbox_event WHERE id=?", String.class, eventId));
            boolean delivered = false;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                Message message = rabbit.receive(queueName, 250);
                if (message == null) continue;
                JsonNode payload = new ObjectMapper().readTree(message.getBody());
                if (payload.path("eventId").asLong() == eventId && payload.path("productId").asLong() == 999999L) {
                    delivered = true;
                    break;
                }
            }
            assertTrue(delivered, "the Outbox event should reach the bound RabbitMQ queue");
        } finally {
            if (eventId != null) jdbc.update("DELETE FROM pms_product_outbox_event WHERE id=?", eventId);
            admin.deleteQueue(queueName);
            connectionFactory.destroy();
        }
    }
}