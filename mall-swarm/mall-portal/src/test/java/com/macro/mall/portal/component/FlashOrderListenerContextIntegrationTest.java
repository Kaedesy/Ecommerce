package com.macro.mall.portal.component;

import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the actual portal Spring context, publisher schedule, and Rabbit listener on isolated validation services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=16379",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.virtual-host=/mall-listener-it",
        "spring.rabbitmq.username=mall",
        "spring.rabbitmq.password=mall",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.boot.admin.client.enabled=false",
        "mall.outbox.enabled=false",
        "mall.reconciliation.enabled=false",
        "mall.flash.lifecycle-poll-ms=3600000",
        "mall.flash.compensation-poll-ms=3600000",
        "mall.flash.init-recovery-poll-ms=3600000"
})
@ActiveProfiles("listener-it")
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class FlashOrderListenerContextIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FlashPromotionRequestDao requestDao;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @Test
    void scheduledPublisherAndRabbitListenerCreateOnlyOneOrderForDuplicateMessage() throws Exception {
        Map<String, Object> originalRelation = jdbc.queryForMap(
                "SELECT flash_promotion_id,flash_promotion_session_id,product_id,product_sku_id, " +
                        "flash_promotion_price,flash_promotion_count,flash_promotion_limit,reserved_count,sold_count, " +
                        "stock_status,remaining_released FROM sms_flash_promotion_product_relation WHERE id=1");
        Integer previousLimitRows = jdbc.queryForObject("SELECT COUNT(*) FROM sms_flash_promotion_user_limit " +
                "WHERE promotion_relation_id=1 AND member_id=1", Integer.class);
        String requestNo = UUID.randomUUID().toString().replace("-", "");
        boolean relationChanged = false;
        boolean requestInserted = false;
        Long orderId = null;

        try {
            jdbc.update("UPDATE sms_flash_promotion_product_relation SET product_sku_id=110, " +
                    "flash_promotion_price=1.00,flash_promotion_count=10,reserved_count=0,sold_count=0, " +
                    "flash_promotion_limit=1,stock_status='ACTIVE',remaining_released=0 WHERE id=1");
            relationChanged = true;
            FlashPromotionRequest request = new FlashPromotionRequest();
            request.setRequestNo(requestNo);
            request.setMemberId(1L);
            request.setPromotionRelationId(1L);
            request.setProductSkuId(110L);
            request.setReceiveAddressId(1L);
            request.setQuantity(1);
            request.setIdempotencyKey("listener-it-" + requestNo);
            request.setRequestHash("c".repeat(64));
            requestDao.insertInit(request);
            requestInserted = true;
            assertEquals(1, requestDao.markReady(requestNo));

            long orderDeadline = System.currentTimeMillis() + 15000;
            String requestStatus = null;
            while (System.currentTimeMillis() < orderDeadline) {
                requestStatus = jdbc.queryForObject("SELECT status FROM sms_flash_promotion_request " +
                        "WHERE request_no=?", String.class, requestNo);
                if ("ORDER_CREATED".equals(requestStatus)) break;
                Thread.sleep(100);
            }
            assertEquals("ORDER_CREATED", requestStatus,
                    "the scheduled publisher should send the request and the real Rabbit listener should create the order");
            orderId = jdbc.queryForObject("SELECT order_id FROM sms_flash_promotion_request WHERE request_no=?",
                    Long.class, requestNo);
            assertNotNull(orderId);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_item WHERE order_id=?", Integer.class, orderId));

            rabbitTemplate.convertAndSend("mall.flash.exchange", "mall.flash.order.create", requestNo);
            Thread.sleep(1000);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE id=?", Integer.class, orderId),
                    "a duplicate Rabbit delivery must not create a second order");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_item WHERE order_id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject("SELECT reserved_count FROM sms_flash_promotion_product_relation " +
                    "WHERE id=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT reserved_count FROM sms_flash_promotion_user_limit " +
                    "WHERE promotion_relation_id=1 AND member_id=1", Integer.class));
            assertTrue(rabbitAdmin.getQueueInfo("mall.flash.order.create").getMessageCount() == 0,
                    "the actual Rabbit listener should acknowledge both messages");
        } finally {
            if (orderId != null) {
                jdbc.update("DELETE FROM oms_order_item WHERE order_id=?", orderId);
                jdbc.update("DELETE FROM oms_outbox_event WHERE order_id=?", orderId);
                jdbc.update("DELETE FROM oms_order WHERE id=?", orderId);
            }
            if (requestInserted) jdbc.update("DELETE FROM sms_flash_promotion_request WHERE request_no=?", requestNo);
            if (previousLimitRows != null && previousLimitRows == 0) {
                jdbc.update("DELETE FROM sms_flash_promotion_user_limit WHERE promotion_relation_id=1 AND member_id=1");
            }
            if (relationChanged) jdbc.update("UPDATE sms_flash_promotion_product_relation SET flash_promotion_id=?, " +
                            "flash_promotion_session_id=?,product_id=?,product_sku_id=?,flash_promotion_price=?, " +
                            "flash_promotion_count=?,flash_promotion_limit=?,reserved_count=?,sold_count=?,stock_status=?, " +
                            "remaining_released=? WHERE id=1",
                    originalRelation.get("flash_promotion_id"), originalRelation.get("flash_promotion_session_id"),
                    originalRelation.get("product_id"), originalRelation.get("product_sku_id"),
                    originalRelation.get("flash_promotion_price"), originalRelation.get("flash_promotion_count"),
                    originalRelation.get("flash_promotion_limit"), originalRelation.get("reserved_count"),
                    originalRelation.get("sold_count"), originalRelation.get("stock_status"),
                    originalRelation.get("remaining_released"));
        }
    }
}
