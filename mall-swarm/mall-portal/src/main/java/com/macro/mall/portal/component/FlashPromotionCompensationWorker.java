package com.macro.mall.portal.component;

import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** 可靠处理请求拒绝和订单取消产生的 Redis 库存补偿。 */
@Component
public class FlashPromotionCompensationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashPromotionCompensationWorker.class);
    private static final RedisScript<Long> RESTORE_SCRIPT = loadRedisScript();
private static RedisScript<Long> loadRedisScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/flash_restore.lua"));
        script.setResultType(Long.class);
        return script;
    }
    private final FlashPromotionRequestDao requestDao;
    private final StringRedisTemplate redisTemplate;
    public FlashPromotionCompensationWorker(FlashPromotionRequestDao requestDao, StringRedisTemplate redisTemplate) {
        this.requestDao = requestDao;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${mall.flash.compensation-poll-ms:1000}")
    public void restorePendingReservations() {
        List<FlashPromotionRequest> pending = requestDao.selectRestorePending(50);
        for (FlashPromotionRequest request : pending) {
            String relation = request.getPromotionRelationId().toString();
            String tag = "{" + relation + "}";
            try {
                Long result = redisTemplate.execute(RESTORE_SCRIPT, Arrays.asList(
                                "flash:" + tag + ":stock", "flash:" + tag + ":users",
                                "flash:" + tag + ":status", "flash:" + tag + ":request:" + request.getRequestNo()),
                        request.getRequestNo());
                if (result != null && result >= 0) requestDao.markRestored(request.getRequestNo());
                else LOGGER.error("Flash reservation {} could not be restored; result={}", request.getRequestNo(), result);
            } catch (Exception exception) {
                LOGGER.error("Flash reservation {} compensation failed", request.getRequestNo(), exception);
            }
        }
    }
}