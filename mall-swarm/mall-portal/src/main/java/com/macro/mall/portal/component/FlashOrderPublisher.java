package com.macro.mall.portal.component;

import com.macro.mall.portal.config.FlashPromotionRabbitConfig;
import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 从数据库可靠扫描待发布请求；重复消息由消费者按 requestNo 幂等处理。 */
@Component
public class FlashOrderPublisher {
    private final FlashPromotionRequestDao requestDao;
    private final RabbitTemplate rabbitTemplate;
    public FlashOrderPublisher(FlashPromotionRequestDao requestDao, RabbitTemplate rabbitTemplate) {
        this.requestDao = requestDao;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${mall.flash.publisher-poll-ms:500}")
    public void publishReadyRequests() {
        publishReadyRequests(FlashPromotionRabbitConfig.EXCHANGE, FlashPromotionRabbitConfig.ROUTING_KEY);
    }

    void publishReadyRequests(String exchange, String routingKey) {
        List<String> requestNos = requestDao.selectReadyRequestNos(50);
        for (String requestNo : requestNos) {
            if (requestDao.claimForPublish(requestNo) != 1) continue;
            try {
                CorrelationData correlation = new CorrelationData(requestNo);
                rabbitTemplate.convertAndSend(exchange, routingKey, requestNo, message -> {
                            message.getMessageProperties().setMessageId(requestNo);
                            message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                            return message;
                        }, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("Flash request was not routed: " + confirm.getReason());
                }
                requestDao.markPublished(requestNo);
            } catch (Exception exception) {
                requestDao.markPublishRetry(requestNo);
                // Confirm 结果未知时只重试投递，不释放 Redis 预占。
            }
        }
    }
}
