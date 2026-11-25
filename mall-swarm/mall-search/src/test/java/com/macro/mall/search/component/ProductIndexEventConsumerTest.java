package com.macro.mall.search.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.search.service.EsProductService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductIndexEventConsumerTest {
    @Test
    void eventRefreshesTheCurrentProductView() throws Exception {
        EsProductService service = mock(EsProductService.class);
        ProductIndexEventConsumer consumer = new ProductIndexEventConsumer(new ObjectMapper(), service);

        consumer.consume("{\"eventId\":41,\"productId\":72}");

        verify(service).refreshIndexById(72L);
    }

    @Test
    void malformedEventIsRejectedForRetryAndDeadLetterHandling() {
        ProductIndexEventConsumer consumer = new ProductIndexEventConsumer(new ObjectMapper(), mock(EsProductService.class));
        assertThrows(Exception.class, () -> consumer.consume("{\"eventId\":41}"));
    }
}
