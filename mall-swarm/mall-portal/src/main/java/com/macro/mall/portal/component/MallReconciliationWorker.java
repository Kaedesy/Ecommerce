package com.macro.mall.portal.component;

import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Periodically repairs safe expired orders and records transaction inconsistencies for review. */
@Component
@ConditionalOnProperty(name = "mall.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class MallReconciliationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(MallReconciliationWorker.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final OmsOrderSettingMapper orderSettingMapper;
    private final OmsPortalOrderService orderService;

    public MallReconciliationWorker(JdbcTemplate jdbcTemplate, OmsOrderSettingMapper orderSettingMapper,
                                   OmsPortalOrderService orderService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderSettingMapper = orderSettingMapper;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${mall.reconciliation.interval-ms:300000}")
    public void reconcile() {
        reconcileExpiredOrders();
        recordInventoryMismatches();
        recordCouponMismatches();
        recordDeadOutboxEvents();
    }

    private void reconcileExpiredOrders() {
        OmsOrderSetting setting = orderSettingMapper.selectByPrimaryKey(1L);
        if (setting == null || setting.getNormalOrderOvertime() == null || setting.getNormalOrderOvertime() <= 0) {
            LOGGER.warn("Skipping expired-order reconciliation because normalOrderOvertime is not configured");
            return;
        }
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT id FROM oms_order WHERE status=0 AND order_type=0 " +
                        "AND create_time<=DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
                        "ORDER BY id LIMIT ?", Long.class, setting.getNormalOrderOvertime(), BATCH_SIZE);
        for (Long orderId : orderIds) {
            try {
                // Reuses the same conditional status transition and inventory/coupon release transaction.
                orderService.cancelOrder(orderId);
                Integer status = jdbcTemplate.queryForObject(
                        "SELECT status FROM oms_order WHERE id=?", Integer.class, orderId);
                String outcome = status != null && status == 4 ? "AUTO_REPAIRED" : "RESOLVED";
                record("EXPIRED_PENDING_ORDER", orderId.toString(), outcome,
                        "超时待支付订单已由现有条件取消流程处理，当前状态=" + status);
            } catch (Exception exception) {
                record("EXPIRED_PENDING_ORDER", orderId.toString(), "NEEDS_REVIEW",
                        "超时订单自动取消失败：" + summarize(exception));
                LOGGER.error("Failed to reconcile expired order {}", orderId, exception);
            }
        }
    }

    private void recordInventoryMismatches() {
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                "SELECT s.id AS business_id, s.lock_stock AS locked_stock, " +
                        "COALESCE(SUM(CASE WHEN o.status=0 AND o.order_type=0 THEN oi.product_quantity ELSE 0 END),0) AS expected_stock " +
                        "FROM pms_sku_stock s " +
                        "LEFT JOIN oms_order_item oi ON oi.product_sku_id=s.id " +
                        "LEFT JOIN oms_order o ON o.id=oi.order_id " +
                        "GROUP BY s.id,s.lock_stock " +
                        "HAVING s.lock_stock <> expected_stock LIMIT ?", BATCH_SIZE);
        for (Map<String, Object> mismatch : mismatches) {
            record("SKU_LOCK_MISMATCH", mismatch.get("business_id").toString(), "NEEDS_REVIEW",
                    "lock_stock=" + mismatch.get("locked_stock") + ", pending_normal_order_quantity=" + mismatch.get("expected_stock"));
        }
        if (!mismatches.isEmpty()) LOGGER.error("Reconciliation found {} SKU lock-stock discrepancies", mismatches.size());
    }

    private void recordCouponMismatches() {
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                "SELECT h.id AS business_id, h.order_id, h.order_sn, o.status AS order_status " +
                        "FROM sms_coupon_history h LEFT JOIN oms_order o ON o.id=h.order_id " +
                        "WHERE h.use_status=1 AND (o.id IS NULL OR o.status IN (4,5)) LIMIT ?", BATCH_SIZE);
        for (Map<String, Object> mismatch : mismatches) {
            record("COUPON_ORDER_MISMATCH", mismatch.get("business_id").toString(), "NEEDS_REVIEW",
                    "coupon_order_id=" + mismatch.get("order_id") + ", order_sn=" + mismatch.get("order_sn") +
                            ", order_status=" + mismatch.get("order_status"));
        }
        if (!mismatches.isEmpty()) LOGGER.error("Reconciliation found {} coupon/order discrepancies", mismatches.size());
    }

    private void recordDeadOutboxEvents() {
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT id, order_id, last_error FROM oms_outbox_event WHERE status='DEAD' ORDER BY id LIMIT ?", BATCH_SIZE);
        for (Map<String, Object> event : events) {
            record("ORDER_OUTBOX_DEAD", "event:" + event.get("id"), "NEEDS_REVIEW",
                    "order_id=" + event.get("order_id") + ", last_error=" + event.get("last_error"));
        }
        if (!events.isEmpty()) LOGGER.error("Reconciliation found {} dead order Outbox events", events.size());

        List<Map<String, Object>> productEvents = jdbcTemplate.queryForList(
                "SELECT id, product_id, last_error FROM pms_product_outbox_event " +
                        "WHERE status='DEAD' ORDER BY id LIMIT ?", BATCH_SIZE);
        for (Map<String, Object> event : productEvents) {
            record("PRODUCT_OUTBOX_DEAD", "event:" + event.get("id"), "NEEDS_REVIEW",
                    "product_id=" + event.get("product_id") + ", last_error=" + event.get("last_error"));
        }
        if (!productEvents.isEmpty()) LOGGER.error("Reconciliation found {} dead product Outbox events", productEvents.size());
    }

    private void record(String type, String businessKey, String status, String details) {
        String boundedDetails = details == null ? "" : details.substring(0, Math.min(details.length(), 2000));
        jdbcTemplate.update("INSERT INTO oms_reconciliation_record " +
                        "(check_type,business_key,status,details,occurrence_count,first_seen,last_seen) " +
                        "VALUES (?,?,?,?,1,NOW(),NOW()) " +
                        "ON DUPLICATE KEY UPDATE status=VALUES(status),details=VALUES(details), " +
                        "occurrence_count=occurrence_count+1,last_seen=NOW(), " +
                        "resolved_at=CASE WHEN VALUES(status) IN ('AUTO_REPAIRED','RESOLVED') THEN NOW() ELSE NULL END",
                type, businessKey, status, boundedDetails);
    }

    private String summarize(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() > 1500 ? message.substring(0, 1500) : message;
    }
}
