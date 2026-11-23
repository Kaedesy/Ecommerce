package com.macro.mall.portal.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 预热/开启/关闭秒杀活动，并在 Redis 状态丢失时 fail closed。 */
@Component
public class FlashPromotionLifecycleWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashPromotionLifecycleWorker.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    public FlashPromotionLifecycleWorker(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                         TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${mall.flash.lifecycle-poll-ms:3000}")
    public void refreshActivities() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT r.id, r.product_sku_id, r.flash_promotion_count, "
                + "r.reserved_count, r.sold_count, r.stock_status, r.remaining_released, "
                + "p.start_date, p.end_date, p.status promotion_status, s.start_time, s.end_time, s.status session_status "
                + "FROM sms_flash_promotion_product_relation r "
                + "JOIN sms_flash_promotion p ON p.id=r.flash_promotion_id "
                + "JOIN sms_flash_promotion_session s ON s.id=r.flash_promotion_session_id "
                + "WHERE r.stock_status IN ('PREPARING','READY','ACTIVE','ENDED','RECOVERY')");
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        for (Map<String, Object> row : rows) {
            Long relationId = ((Number) row.get("id")).longValue();
            String status = (String) row.get("stock_status");
            String stockKey = redisKey(relationId, "stock");
            String statusKey = redisKey(relationId, "status");
            try {
                if ("PREPARING".equals(status)) prepare(row, stockKey, statusKey);
                else if ("READY".equals(status)) activateIfDue(row, now, statusKey);
                else if ("ACTIVE".equals(status) && isPermanentlyEnded(row, now)) closeRedisGate(relationId, statusKey);
                else if ("ACTIVE".equals(status) && !isInWindow(row, now)) pauseBetweenSessions(relationId, statusKey);
                else if ("ACTIVE".equals(status) && "READY".equals(redisTemplate.opsForValue().get(statusKey))) resumeSession(relationId, statusKey);
                else if ("ACTIVE".equals(status) && !isRedisHealthy(row, relationId)) markRecovery(relationId, statusKey);
                else if ("ENDED".equals(status)) releaseRemainingIfReady(row);
                else if ("RECOVERY".equals(status)) LOGGER.error("Seckill relation {} needs Redis/MySQL reconciliation", relationId);
            } catch (Exception exception) {
                LOGGER.error("Could not refresh seckill relation {}", relationId, exception);
            }
        }
    }

    private void prepare(Map<String, Object> row, String stockKey, String statusKey) {
        Long id = ((Number) row.get("id")).longValue();
        Integer sold = ((Number) row.get("sold_count")).intValue();
        Integer reserved = ((Number) row.get("reserved_count")).intValue();
        Integer total = ((Number) row.get("flash_promotion_count")).intValue();
        if (total < sold + reserved) {
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='RECOVERY' WHERE id=? AND stock_status='PREPARING'", id);
            return;
        }
        boolean redisExists = Boolean.TRUE.equals(redisTemplate.hasKey(stockKey));
        long outstanding = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_flash_promotion_request "
                + "WHERE promotion_relation_id=? AND status IN ('INIT','READY','PUBLISHING','PUBLISHED','PROCESSING','RESTORE_PENDING')", Long.class, id);
        if ((sold > 0 || reserved > 0 || outstanding > 0) && !redisExists) {
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='RECOVERY' WHERE id=? AND stock_status='PREPARING'", id);
            return;
        }
        redisTemplate.opsForValue().setIfAbsent(stockKey, Integer.toString(total - sold - reserved));
        redisTemplate.opsForValue().setIfAbsent(statusKey, "READY");
        if (Boolean.TRUE.equals(redisTemplate.hasKey(stockKey)) && Boolean.TRUE.equals(redisTemplate.hasKey(statusKey))) {
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='READY' WHERE id=? AND stock_status='PREPARING'", id);
        }
    }

    private void activateIfDue(Map<String, Object> row, LocalDateTime now, String statusKey) {
        Long id = ((Number) row.get("id")).longValue();
        if (!isCampaignEnabled(row) || !isInWindow(row, now)) return;
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(id, "stock")))) {
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='RECOVERY' WHERE id=? AND stock_status='READY'", id);
            return;
        }
        redisTemplate.opsForValue().set(statusKey, "ACTIVE");
        jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='ACTIVE' WHERE id=? AND stock_status='READY'", id);
    }

    private boolean isRedisHealthy(Map<String, Object> row, Long relationId) {
        String status = redisTemplate.opsForValue().get(redisKey(relationId, "status"));
        if (!"ACTIVE".equals(status) || !Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(relationId, "stock")))) return false;
        Long activeLimits = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_flash_promotion_user_limit "
                + "WHERE promotion_relation_id=? AND (reserved_count>0 OR bought_count>0)", Long.class, relationId);
        return activeLimits == null || activeLimits == 0 || Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(relationId, "users")));
    }

    private boolean hasRequiredRedisKeys(Long relationId) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(relationId, "stock")))) return false;
        Long activeLimits = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_flash_promotion_user_limit "
                + "WHERE promotion_relation_id=? AND (reserved_count>0 OR bought_count>0)", Long.class, relationId);
        return activeLimits == null || activeLimits == 0 || Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(relationId, "users")));
    }

    private boolean isPermanentlyEnded(Map<String, Object> row, LocalDateTime now) {
        if (!isCampaignEnabled(row)) return true;
        Date endDate = (Date) row.get("end_date");
        Time endTime = (Time) row.get("end_time");
        return endDate != null && (now.toLocalDate().isAfter(endDate.toLocalDate())
                || (now.toLocalDate().equals(endDate.toLocalDate()) && endTime != null
                && !now.toLocalTime().isBefore(endTime.toLocalTime())));
    }
    private void closeRedisGate(Long relationId, String statusKey) {
        redisTemplate.opsForValue().set(statusKey, "ENDED");
        jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='ENDED' WHERE id=? AND stock_status='ACTIVE'", relationId);
    }

    private void pauseBetweenSessions(Long relationId, String statusKey) {
        // 场次间关闭新请求入口，但保留活动配额并允许已排队请求继续处理。
        redisTemplate.opsForValue().set(statusKey, "READY");
    }

    private void resumeSession(Long relationId, String statusKey) {
        if (!hasRequiredRedisKeys(relationId)) {
            markRecovery(relationId, statusKey);
            return;
        }
        redisTemplate.opsForValue().set(statusKey, "ACTIVE");
    }

    private void markRecovery(Long relationId, String statusKey) {
        redisTemplate.opsForValue().set(statusKey, "RECOVERY");
        jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET stock_status='RECOVERY' WHERE id=? AND stock_status='ACTIVE'", relationId);
        LOGGER.error("Seckill relation {} lost Redis state; intake is paused for reconciliation", relationId);
    }

    private void releaseRemainingIfReady(Map<String, Object> row) {
        Long id = ((Number) row.get("id")).longValue();
        transactionTemplate.execute(status -> {
            Map<String, Object> locked = jdbcTemplate.queryForMap("SELECT product_sku_id, flash_promotion_count, reserved_count, sold_count, remaining_released "
                    + "FROM sms_flash_promotion_product_relation WHERE id=? AND stock_status='ENDED' FOR UPDATE", id);
            if (((Number) locked.get("remaining_released")).intValue() != 0) return null;
            Long pending = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_flash_promotion_request "
                    + "WHERE promotion_relation_id=? AND status IN ('INIT','READY','PUBLISHING','PUBLISHED','PROCESSING','RESTORE_PENDING')", Long.class, id);
            if (pending != null && pending > 0) return null;
            int total = ((Number) locked.get("flash_promotion_count")).intValue();
            int reserved = ((Number) locked.get("reserved_count")).intValue();
            int sold = ((Number) locked.get("sold_count")).intValue();
            int unused = Math.max(0, total - reserved - sold);
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET remaining_released=1 WHERE id=? AND remaining_released=0", id);
            if (unused > 0) {
                int released = jdbcTemplate.update("UPDATE pms_sku_stock SET lock_stock=GREATEST(COALESCE(lock_stock,0),0)-? "
                                + "WHERE id=? AND GREATEST(COALESCE(lock_stock,0),0)>=?",
                        unused, locked.get("product_sku_id"), unused);
                if (released != 1) throw new IllegalStateException("Unable to release unused flash stock for relation " + id);
            }
            return null;
        });
    }

    private boolean isCampaignEnabled(Map<String, Object> row) {
        return ((Number) row.get("promotion_status")).intValue() == 1 && ((Number) row.get("session_status")).intValue() == 1;
    }

    private boolean isInWindow(Map<String, Object> row, LocalDateTime now) {
        if (!isCampaignEnabled(row)) return false;
        Date startDate = (Date) row.get("start_date");
        Date endDate = (Date) row.get("end_date");
        Time startTime = (Time) row.get("start_time");
        Time endTime = (Time) row.get("end_time");
        return startDate != null && endDate != null && startTime != null && endTime != null
                && !now.toLocalDate().isBefore(startDate.toLocalDate())
                && !now.toLocalDate().isAfter(endDate.toLocalDate())
                && !now.toLocalTime().isBefore(startTime.toLocalTime())
                && now.toLocalTime().isBefore(endTime.toLocalTime());
    }

    private String redisKey(Long id, String suffix) { return "flash:{" + id + "}:" + suffix; }
}