package com.macro.mall.portal.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exercises the actual seckill reservation and compensation Lua scripts on validation Redis. */
@EnabledIfSystemProperty(named = "mall.test.integration", matches = "true")
class FlashPromotionLuaIntegrationTest {
    @Test
    void concurrentReservationsNeverOversellAndCompensationIsIdempotent() throws Exception {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String relation = UUID.randomUUID().toString().replace("-", "");
        String requestPrefix = "flash:{" + relation + "}:request:";
        List<String> keys = List.of("flash:{" + relation + "}:stock", "flash:{" + relation + "}:users",
                "flash:{" + relation + "}:status");
        DefaultRedisScript<Long> reserve = script("lua/flash_reserve.lua");
        DefaultRedisScript<Long> restore = script("lua/flash_restore.lua");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<String> requestNos = new ArrayList<>();

        try {
            redis.opsForValue().set(keys.get(0), "10");
            redis.opsForValue().set(keys.get(2), "ACTIVE");
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String requestNo = "req-" + i;
                requestNos.add(requestNo);
                String memberId = "member-" + i;
                results.add(pool.submit(() -> redis.execute(reserve,
                        List.of(keys.get(0), keys.get(1), keys.get(2), requestPrefix + requestNo),
                        requestNo, memberId, "1", "1", "3600")));
            }
            int accepted = 0;
            for (Future<Long> result : results) {
                Long remainingStock = result.get();
                assertNotNull(remainingStock);
                if (remainingStock >= 0) accepted++;
            }
            assertEquals(10, accepted, "exactly the available stock should be reserved");
            assertEquals("0", redis.opsForValue().get(keys.get(0)));

            String firstRequestKey = requestPrefix + "req-0";
            Long replayResult = redis.execute(reserve, List.of(keys.get(0), keys.get(1), keys.get(2), firstRequestKey),
                    "req-0", "member-0", "1", "1", "3600");
            assertNotNull(replayResult);
            assertEquals("0", redis.opsForValue().get(keys.get(0)), "replaying a reservation token must not decrement twice");

            String restoreResultKey = firstRequestKey;
            List<String> restoreKeys = List.of(keys.get(0), keys.get(1), keys.get(2), restoreResultKey);
            assertEquals(1L, redis.execute(restore, restoreKeys, "req-0"));
            assertEquals(0L, redis.execute(restore, restoreKeys, "req-0"), "repeated compensation must be harmless");
            assertEquals("1", redis.opsForValue().get(keys.get(0)));
            assertNull(redis.opsForHash().get(keys.get(1), "member-0"));
        } finally {
            pool.shutdownNow();
            redis.delete(keys);
            for (String requestNo : requestNos) redis.delete(requestPrefix + requestNo);
            factory.destroy();
        }
    }

    @Test
    void rejectsInactiveMissingStockSoldOutAndOverLimitRequests() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String relation = UUID.randomUUID().toString().replace("-", "");
        List<String> keys = List.of("flash:{" + relation + "}:stock", "flash:{" + relation + "}:users",
                "flash:{" + relation + "}:status", "flash:{" + relation + "}:request:edge");
        DefaultRedisScript<Long> reserve = script("lua/flash_reserve.lua");

        try {
            redis.opsForValue().set(keys.get(0), "5");
            redis.opsForValue().set(keys.get(2), "PREPARING");
            assertEquals(-1L, redis.execute(reserve, keys, "edge", "member-edge", "1", "1", "3600"));

            redis.opsForValue().set(keys.get(2), "ACTIVE");
            redis.delete(keys.get(0));
            assertEquals(-5L, redis.execute(reserve, keys, "edge", "member-edge", "1", "1", "3600"));

            redis.opsForValue().set(keys.get(0), "0");
            assertEquals(-2L, redis.execute(reserve, keys, "edge", "member-edge", "1", "1", "3600"));

            redis.opsForValue().set(keys.get(0), "5");
            redis.opsForHash().put(keys.get(1), "member-edge", "1");
            assertEquals(-3L, redis.execute(reserve, keys, "edge", "member-edge", "1", "1", "3600"));
            assertEquals("5", redis.opsForValue().get(keys.get(0)), "rejected requests must not change stock");
        } finally {
            redis.delete(keys);
            factory.destroy();
        }
    }

    private DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
