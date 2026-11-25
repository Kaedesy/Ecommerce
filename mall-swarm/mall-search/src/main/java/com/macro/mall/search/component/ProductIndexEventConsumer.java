package com.macro.mall.search.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.search.config.ProductIndexRabbitConfig;
import com.macro.mall.search.service.EsProductService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ProductIndexEventConsumer {
    private final ObjectMapper objectMapper;
    private final EsProductService esProductService;

    public ProductIndexEventConsumer(ObjectMapper objectMapper, EsProductService esProductService) {
        this.objectMapper = objectMapper;
        this.esProductService = esProductService;
    }

    @RabbitListener(queues = ProductIndexRabbitConfig.QUEUE,
            containerFactory = "productIndexListenerFactory")
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (event == null || !event.hasNonNull("eventId") || !event.hasNonNull("productId")) {
            throw new IllegalArgumentException("Product index event must contain eventId and productId");
        }
        long productId = event.get("productId").asLong(-1);
        if (productId <= 0) throw new IllegalArgumentException("Invalid productId in index event");
        // Re-read the authoritative row so replayed/out-of-order events converge to current data.
        esProductService.refreshIndexById(productId);
    }
}
