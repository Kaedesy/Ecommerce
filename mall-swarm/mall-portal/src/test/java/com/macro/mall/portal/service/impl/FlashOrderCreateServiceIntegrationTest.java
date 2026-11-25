package com.macro.mall.portal.service.impl;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.mapper.UmsMemberReceiveAddressMapper;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberReceiveAddress;
import com.macro.mall.portal.component.FlashOrderConsumer;
import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies transactional seckill order creation and duplicate delivery against validation MySQL. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class FlashOrderCreateServiceIntegrationTest {
    @Test
    void duplicateDeliveryCreatesOneOrderAndOneReservation() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", "root");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
        factoryBean.setMapperLocations(
                resources.getResources("classpath:dao/FlashPromotionRequestDao.xml")[0],
                resources.getResources("classpath*:com/macro/mall/mapper/OmsOrderMapper.xml")[0],
                resources.getResources("classpath*:com/macro/mall/mapper/OmsOrderItemMapper.xml")[0]);
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        assertNotNull(sqlSessionFactory);
        SqlSessionTemplate sessions = new SqlSessionTemplate(sqlSessionFactory);
        FlashPromotionRequestDao requestDao = sessions.getMapper(FlashPromotionRequestDao.class);

        OmsOrderMapper orderMapper = sessions.getMapper(OmsOrderMapper.class);
        OmsOrderItemMapper orderItemMapper = sessions.getMapper(OmsOrderItemMapper.class);
        OmsOrderSettingMapper settings = mock(OmsOrderSettingMapper.class);
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

        FlashOrderCreateService createService = new FlashOrderCreateService(requestDao, jdbc, orderMapper,
                orderItemMapper, settings, addresses, members, orders);
        FlashOrderConsumer consumer = new FlashOrderConsumer(createService, requestDao);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String requestNo = java.util.UUID.randomUUID().toString().replace("-", "");

        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE sms_flash_promotion_product_relation SET product_sku_id=110, " +
                            "flash_promotion_price=1.00, flash_promotion_count=10, reserved_count=0, sold_count=0, " +
                            "flash_promotion_limit=1, stock_status='ACTIVE' WHERE id=1");
            FlashPromotionRequest request = new FlashPromotionRequest();
            request.setRequestNo(requestNo);
            request.setMemberId(1L);
            request.setPromotionRelationId(1L);
            request.setProductSkuId(110L);
            request.setReceiveAddressId(1L);
            request.setQuantity(1);
            request.setIdempotencyKey("integration-" + requestNo);
            request.setRequestHash("b".repeat(64));
            requestDao.insertInit(request);
            requestDao.markReady(requestNo);
            requestDao.claimForPublish(requestNo);
            requestDao.markPublished(requestNo);

            consumer.consume(requestNo);
            consumer.consume(requestNo);

            assertEquals("ORDER_CREATED", jdbc.queryForObject(
                    "SELECT status FROM sms_flash_promotion_request WHERE request_no=?", String.class, requestNo));
            Long orderId = jdbc.queryForObject(
                    "SELECT order_id FROM sms_flash_promotion_request WHERE request_no=?", Long.class, requestNo);
            assertNotNull(orderId);
            String orderSn = jdbc.queryForObject("SELECT order_sn FROM oms_order WHERE id=?", String.class, orderId);
            assertNotNull(orderSn);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_item WHERE order_id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT reserved_count FROM sms_flash_promotion_product_relation WHERE id=1", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT reserved_count FROM sms_flash_promotion_user_limit " +
                            "WHERE promotion_relation_id=1 AND member_id=1", Integer.class));
            verify(orders, times(1)).sendDelayMessageCancelOrder(orderId);
            status.setRollbackOnly();
        });
    }
}
