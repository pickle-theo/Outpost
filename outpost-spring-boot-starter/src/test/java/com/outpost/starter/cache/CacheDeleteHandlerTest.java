package com.outpost.starter.cache;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class CacheDeleteHandlerTest {

    private static StringRedisTemplate redis;
    private static CacheDeleteHandler handler;

    @BeforeAll
    static void setUp() {
        redis = RedisTestSupport.template();
        handler = new CacheDeleteHandler(redis, new TimeoutRedisExecutor(2000L));
    }

    @BeforeEach
    void flush() {
        RedisTestSupport.flushAll();
    }

    @Test
    void deliverShouldDeleteExistingKey() throws Exception {
        String key = "delete:" + UUID.randomUUID();
        redis.opsForValue().set(key, "v1");

        handler.deliver(message(key));

        assertFalse(redis.hasKey(key));
    }

    @Test
    void deliverShouldNotFailWhenKeyMissing() throws Exception {
        String key = "delete:" + UUID.randomUUID();
        assertFalse(redis.hasKey(key));

        handler.deliver(message(key));

        assertFalse(redis.hasKey(key));
    }

    @Test
    void handlerShouldExposeCacheDeleteBizType() {
        assertTrue(CacheBizTypes.CACHE_DELETE.equals(handler.getBizType()));
    }

    private OutboxDeliveryMessage message(String key) {
        return new OutboxDeliveryMessage(1L, CacheBizTypes.CACHE_DELETE,
                "order:" + key, key, 0, "trace", null);
    }
}
