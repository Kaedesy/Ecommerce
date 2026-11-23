package com.macro.mall.portal.component;

import com.macro.mall.portal.config.FlashPromotionRabbitConfig;
import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.service.impl.FlashOrderCreateService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FlashOrderConsumer {
    private final FlashOrderCreateService createService;
    private final FlashPromotionRequestDao requestDao;
    public FlashOrderConsumer(FlashOrderCreateService createService, FlashPromotionRequestDao requestDao) {
        this.createService = createService;
        this.requestDao = requestDao;
    }

    @RabbitListener(queues = FlashPromotionRabbitConfig.QUEUE, containerFactory = "flashListenerFactory")
    public void consume(String requestNo) {
        createService.createOrder(requestNo);
    }

    @RabbitListener(queues = FlashPromotionRabbitConfig.DEAD_QUEUE)
    public void deadLetter(String requestNo) {
        requestDao.markRestorePending(requestNo, "秒杀建单消息进入 DLQ，等待库存补偿");
    }
}