package com.macro.mall.portal.component;

import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import com.macro.mall.portal.service.impl.FlashOrderCreateService;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberReceiveAddress;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.mapper.UmsMemberReceiveAddressMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies real MyBatis request dispatch and RabbitMQ publisher confirms using validation services. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class FlashOrderPublisherIntegrationTest {
    @Test
    void readyRequestIsClaimedPublishedAndRoutedToRabbit() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", "root");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
        factoryBean.setMapperLocations(resources.getResources("classpath:dao/FlashPromotionRequestDao.xml")[0],
                resources.getResources("classpath*:com/macro/mall/mapper/OmsOrderMapper.xml")[0],
                resources.getResources("classpath*:com/macro/mall/mapper/OmsOrderItemMapper.xml")[0]);
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        assertNotNull(sqlSessionFactory);
        SqlSessionTemplate sessions = new SqlSessionTemplate(sqlSessionFactory);
        FlashPromotionRequestDao requestDao = sessions.getMapper(FlashPromotionRequestDao.class);
        OmsOrderMapper orderMapper = sessions.getMapper(OmsOrderMapper.class);
        OmsOrderItemMapper orderItemMapper = sessions.getMapper(OmsOrderItemMapper.class);
        OmsOrderSettingMapper orderSettings = mock(OmsOrderSettingMapper.class);
        UmsMemberReceiveAddressMapper addresses = mock(UmsMemberReceiveAddressMapper.class);
        UmsMemberService members = mock(UmsMemberService.class);
        OmsPortalOrderService orders = mock(OmsPortalOrderService.class);
        UmsMember member = new UmsMember();
        member.setId(1L);
        member.setUsername("validation-member");
        when(members.getById(1L)).thenReturn(member);
        UmsMemberReceiveAddress address = new UmsMemberReceiveAddress();
        address.setId(1L);
        address.setMemberId(1L);
        address.setName("validation-address");
        address.setPhoneNumber("13800000000");
        when(addresses.selectByPrimaryKey(1L)).thenReturn(address);
        FlashOrderConsumer consumer = new FlashOrderConsumer(new FlashOrderCreateService(requestDao, jdbc,
                orderMapper, orderItemMapper, orderSettings, addresses, members, orders), requestDao);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost", 5672);
        connectionFactory.setVirtualHost("/mall");
        connectionFactory.setUsername("mall");
        connectionFactory.setPassword("mall");
        connectionFactory.setPublisherConfirmType(ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate rabbit = new RabbitTemplate(connectionFactory);
        rabbit.setMandatory(true);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        String queueName = "mall.flash.integration." + UUID.randomUUID();
        String exchangeName = "mall.flash.integration.exchange." + UUID.randomUUID();
        String unroutedExchange = "mall.flash.integration.unrouted." + UUID.randomUUID();
        String routingKey = "mall.flash.integration.route";
        String requestNo = UUID.randomUUID().toString().replace("-", "");
        boolean inserted = false;

        try {
            admin.declareExchange(new DirectExchange(exchangeName, true, false));
            admin.declareQueue(new Queue(queueName, false, false, true));
            admin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                    exchangeName, routingKey, null));

            FlashPromotionRequest request = new FlashPromotionRequest();
            request.setRequestNo(requestNo);
            request.setMemberId(1L);
            request.setPromotionRelationId(1L);
            request.setProductSkuId(110L);
            request.setReceiveAddressId(1L);
            request.setQuantity(1);
            request.setIdempotencyKey("integration-" + requestNo);
            request.setRequestHash("a".repeat(64));
            requestDao.insertInit(request);
            inserted = true;
            assertEquals(1, requestDao.markReady(requestNo));

            FlashOrderPublisher publisher = new FlashOrderPublisher(requestDao, rabbit);
            publisher.publishReadyRequests(exchangeName, routingKey);

            assertEquals("PUBLISHED", jdbc.queryForObject(
                    "SELECT status FROM sms_flash_promotion_request WHERE request_no=?", String.class, requestNo));
            Message message = rabbit.receive(queueName, 3000);
            assertNotNull(message, "the request should be routed to the bound RabbitMQ queue");
            assertEquals(requestNo, new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(requestNo, message.getMessageProperties().getMessageId());

            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE sms_flash_promotion_product_relation SET product_sku_id=110, " +
                        "flash_promotion_price=1.00,flash_promotion_count=10,reserved_count=0,sold_count=0, " +
                        "flash_promotion_limit=1,stock_status='ACTIVE' WHERE id=1");
                consumer.consume(requestNo);
                consumer.consume(requestNo);

                assertEquals("ORDER_CREATED", jdbc.queryForObject(
                        "SELECT status FROM sms_flash_promotion_request WHERE request_no=?", String.class, requestNo));
                Long orderId = jdbc.queryForObject(
                        "SELECT order_id FROM sms_flash_promotion_request WHERE request_no=?", Long.class, requestNo);
                assertNotNull(orderId);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE id=?", Integer.class, orderId));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_item WHERE order_id=?", Integer.class, orderId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT reserved_count FROM sms_flash_promotion_product_relation WHERE id=1", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT reserved_count FROM sms_flash_promotion_user_limit " +
                        "WHERE promotion_relation_id=1 AND member_id=1", Integer.class));
                verify(orders, times(1)).sendDelayMessageCancelOrder(orderId);
                status.setRollbackOnly();
            });

            FlashPromotionRequestDao retryDao = mock(FlashPromotionRequestDao.class);
            String unroutedRequestNo = "unrouted-" + requestNo;
            when(retryDao.selectReadyRequestNos(50)).thenReturn(java.util.List.of(unroutedRequestNo));
            when(retryDao.claimForPublish(unroutedRequestNo)).thenReturn(1);
            admin.declareExchange(new DirectExchange(unroutedExchange, true, false));
            new FlashOrderPublisher(retryDao, rabbit).publishReadyRequests(unroutedExchange, routingKey);
            verify(retryDao).markPublishRetry(unroutedRequestNo);
            verify(retryDao, never()).markPublished(unroutedRequestNo);
        } finally {
            if (inserted) jdbc.update("DELETE FROM sms_flash_promotion_request WHERE request_no=?", requestNo);
            admin.deleteQueue(queueName);
            admin.deleteExchange(exchangeName);
            admin.deleteExchange(unroutedExchange);
            connectionFactory.destroy();
        }
    }
}
