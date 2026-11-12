package com.outpost.starter.cache;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class CacheTagDeleteHandlerTest {

    private static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        redis = RedisTestSupport.template();
    }

    @BeforeEach
    void flush() {
        RedisTestSupport.flushAll();
    }

    @Test
    void deliverShouldMarkLockUntilAndKeepValue() throws Exception {
        String key = "tag:" + UUID.randomUUID();
        redis.opsForHash().put(key, RockscacheScripts.FIELD_VALUE, "v1");
        redis.opsForHash().put(key, RockscacheScripts.FIELD_LOCK_UNTIL,
                String.valueOf(System.currentTimeMillis() / 1000L + 3600L));
        redis.opsForHash().put(key, RockscacheScripts.FIELD_LOCK_OWNER, "owner");
        redis.expire(key, 3600, TimeUnit.SECONDS);

        OutpostProperties properties = new OutpostProperties();
        properties.getDeliveryCache().getTag().setDelaySeconds(10);
        CacheTagDeleteHandler handler =
                new CacheTagDeleteHandler(redis, new TimeoutRedisExecutor(2000L), properties);

        handler.deliver(message(key));

        Map<Object, Object> hash = redis.opsForHash().entries(key);
        assertEquals("0", hash.get(RockscacheScripts.FIELD_LOCK_UNTIL));
        assertFalse(hash.containsKey(RockscacheScripts.FIELD_LOCK_OWNER));
        assertEquals("v1", hash.get(RockscacheScripts.FIELD_VALUE));

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0 && ttl <= 11, "ttl=" + ttl);
    }

    @Test
    void deliverShouldRejectBlankPayload() {
        OutpostProperties properties = new OutpostProperties();
        CacheTagDeleteHandler handler =
                new CacheTagDeleteHandler(redis, new TimeoutRedisExecutor(2000L), properties);

        try {
            handler.deliver(new OutboxDeliveryMessage(1L, CacheBizTypes.CACHE_TAG_DELETE,
                    "ns:", " ", 0, null, null));
            throw new AssertionError("应当抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception e) {
            throw new AssertionError("应当抛出 IllegalArgumentException，实际: " + e, e);
        }
    }

    private OutboxDeliveryMessage message(String key) {
        return new OutboxDeliveryMessage(1L, CacheBizTypes.CACHE_TAG_DELETE,
                "order:" + key, key, 0, "trace", null);
    }
}
