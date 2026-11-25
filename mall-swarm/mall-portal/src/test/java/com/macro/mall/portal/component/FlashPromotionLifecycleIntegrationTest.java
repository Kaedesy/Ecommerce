package com.macro.mall.portal.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies the seckill lifecycle worker fails closed when active Redis stock disappears. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class FlashPromotionLifecycleIntegrationTest {
    @Test
    void missingActiveRedisStockMovesRelationToRecovery() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", "root");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String stockKey = "flash:{1}:stock";
        String statusKey = "flash:{1}:status";
        String usersKey = "flash:{1}:users";
        FlashPromotionLifecycleWorker worker = new FlashPromotionLifecycleWorker(jdbc, redis, transaction);

        try {
            redis.delete(List.of(stockKey, statusKey, usersKey));
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE sms_flash_promotion SET status=1,start_date=CURDATE(),end_date=DATE_ADD(CURDATE(),INTERVAL 1 DAY) WHERE id=14");
                jdbc.update("UPDATE sms_flash_promotion_session SET status=1,start_time='00:00:00', " +
                        "end_time='23:59:59' WHERE id=1");
                jdbc.update("UPDATE sms_flash_promotion_product_relation SET flash_promotion_id=14,product_sku_id=110, " +
                        "flash_promotion_count=10,reserved_count=0,sold_count=0,remaining_released=0, " +
                        "stock_status='ACTIVE' WHERE id=1");
                redis.opsForValue().set(statusKey, "ACTIVE");
                worker.refreshActivities();

                assertEquals("RECOVERY", jdbc.queryForObject(
                        "SELECT stock_status FROM sms_flash_promotion_product_relation WHERE id=1", String.class));
                assertEquals("RECOVERY", redis.opsForValue().get(statusKey));
                assertNull(redis.opsForValue().get(stockKey), "the worker must not recreate missing active stock as sellable");
                status.setRollbackOnly();
            });
        } finally {
            redis.delete(List.of(stockKey, statusKey, usersKey));
            factory.destroy();
        }
    }
}
