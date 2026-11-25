package com.macro.mall.search.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.repository.EsProductRepository;
import com.macro.mall.search.service.impl.EsProductServiceImpl;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Run only against the dedicated mall-swarm Elasticsearch container. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class ProductIndexRefreshIntegrationTest {
    @Test
    void eventRefreshesAndDeletesDocumentsInRealElasticsearch() throws Exception {
        ClientConfiguration clientConfiguration = ClientConfiguration.create("localhost:9200");
        RestClient restClient = ElasticsearchClients.getRestClient(clientConfiguration);
        try {
            ElasticsearchCustomConversions conversions = new ElasticsearchCustomConversions(Collections.emptyList());
            SimpleElasticsearchMappingContext mappingContext = new SimpleElasticsearchMappingContext();
            mappingContext.setInitialEntitySet(Set.of(EsProduct.class));
            mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
            mappingContext.afterPropertiesSet();
            MappingElasticsearchConverter converter = new MappingElasticsearchConverter(mappingContext);
            converter.setConversions(conversions);
            converter.afterPropertiesSet();
            ElasticsearchTemplate template = new ElasticsearchTemplate(
                    ElasticsearchClients.createImperative(restClient), converter);
            ElasticsearchRepositoryFactory repositoryFactory = new ElasticsearchRepositoryFactory(template);
            EsProductRepository repository = repositoryFactory.getRepository(EsProductRepository.class);
            EsProductDao productDao = mock(EsProductDao.class);
            EsProductServiceImpl service = new EsProductServiceImpl();
            ReflectionTestUtils.setField(service, "productDao", productDao);
            ReflectionTestUtils.setField(service, "productRepository", repository);
            ReflectionTestUtils.setField(service, "elasticsearchTemplate", template);
            ProductIndexEventConsumer consumer = new ProductIndexEventConsumer(new ObjectMapper(), service);
            long indexedId = 996650001L;
            long deletedId = 996650002L;
            EsProduct currentProduct = new EsProduct();
            currentProduct.setId(indexedId);
            currentProduct.setName("index-refresh-integration-product");
            when(productDao.getAllEsProductList(indexedId)).thenReturn(List.of(currentProduct));
            when(productDao.getAllEsProductList(deletedId)).thenReturn(List.of());

            try {
                consumer.consume("{\"eventId\":9966500,\"productId\":" + indexedId + "}");
                template.indexOps(EsProduct.class).refresh();
                EsProduct indexed = repository.findById(indexedId).orElseThrow();
                assertEquals("index-refresh-integration-product", indexed.getName());

                EsProduct obsolete = new EsProduct();
                obsolete.setId(deletedId);
                obsolete.setName("obsolete-integration-product");
                repository.save(obsolete);
                template.indexOps(EsProduct.class).refresh();
                assertTrue(repository.existsById(deletedId));
                consumer.consume("{\"eventId\":9966501,\"productId\":" + deletedId + "}");
                template.indexOps(EsProduct.class).refresh();
                assertFalse(repository.existsById(deletedId));
            } finally {
                repository.deleteById(indexedId);
                repository.deleteById(deletedId);
                template.indexOps(EsProduct.class).refresh();
            }
        } finally {
            restClient.close();
        }
    }
}