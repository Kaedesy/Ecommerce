package com.macro.mall.portal.component;

import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Runs the reconciliation SQL against the dedicated validation MySQL database. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class MallReconciliationWorkerIntegrationTest {
    @Test
    void recordsRealInventoryAndCouponMismatchesIdempotently() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://localhost:13306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", "root");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MallReconciliationWorker worker = new MallReconciliationWorker(
                jdbc, mock(OmsOrderSettingMapper.class), mock(OmsPortalOrderService.class));

        transaction.executeWithoutResult(status -> {
            int expectedSkuMismatches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT s.id FROM pms_sku_stock s " +
                            "LEFT JOIN oms_order_item oi ON oi.product_sku_id=s.id " +
                            "LEFT JOIN oms_order o ON o.id=oi.order_id " +
                            "GROUP BY s.id,s.lock_stock " +
                            "HAVING s.lock_stock <> COALESCE(SUM(CASE WHEN o.status=0 AND o.order_type=0 " +
                            "THEN oi.product_quantity ELSE 0 END),0)) mismatches", Integer.class);
            int expectedCouponMismatches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sms_coupon_history h LEFT JOIN oms_order o ON o.id=h.order_id " +
                            "WHERE h.use_status=1 AND (o.id IS NULL OR o.status IN (4,5))", Integer.class);

            worker.reconcile();
            worker.reconcile();

            assertEquals(expectedSkuMismatches, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM oms_reconciliation_record WHERE check_type='SKU_LOCK_MISMATCH'", Integer.class));
            assertEquals(expectedCouponMismatches, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM oms_reconciliation_record WHERE check_type='COUPON_ORDER_MISMATCH'", Integer.class));
            assertTrue(jdbc.queryForObject(
                    "SELECT MIN(occurrence_count) FROM oms_reconciliation_record " +
                            "WHERE check_type IN ('SKU_LOCK_MISMATCH','COUPON_ORDER_MISMATCH')", Integer.class) >= 2);
            status.setRollbackOnly();
        });
    }
}
