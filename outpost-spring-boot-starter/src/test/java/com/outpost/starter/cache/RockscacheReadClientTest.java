package com.outpost.starter.cache;

import com.outpost.core.config.OutpostProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RockscacheReadClientTest {

    private static StringRedisTemplate redis;
    private static ExecutorService asyncPool;
    private static OutpostProperties properties;

    private RockscacheReadClient client;

    @BeforeAll
    static void setUp() {
        redis = RedisTestSupport.template();
        asyncPool = Executors.newFixedThreadPool(4);
        properties = new OutpostProperties();
        properties.getDeliveryCache().getTag().setDelaySeconds(10);
        properties.getDeliveryCache().getTag().setLockExpireMillis(3000L);
        properties.getDeliveryCache().getTag().setLockSleepMillis(20L);
        properties.getDeliveryCache().getTag().setEmptyExpireSeconds(60L);
        properties.getDeliveryCache().getTag().setRandomExpireAdjustment(0.1D);
    }

    @AfterAll
    static void tearDown() {
        asyncPool.shutdownNow();
    }

    @BeforeEach
    void flush() {
        RedisTestSupport.flushAll();
        client = new RockscacheReadClient(redis, properties, asyncPool);
    }

    @Test
    void missShouldLoadOnceAndThenHitCache() {
        String key = "read:miss";
        final AtomicInteger calls = new AtomicInteger();

        String first = client.fetch(key, 120L, () -> {
            calls.incrementAndGet();
            return "v1";
        });
        assertEquals("v1", first);
        assertEquals(1, calls.get());

        String second = client.fetch(key, 120L, () -> {
            calls.incrementAndGet();
            return "v2";
        });
        assertEquals("v1", second);
        assertEquals(1, calls.get());

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0 && ttl <= 110, "ttl=" + ttl);
    }

    @Test
    void taggedKeyShouldReturnOldValueThenRefreshAsync() {
        String key = "read:tagged";
        client.rawSet(key, "old", 120L);
        tagDelete(key);
        final AtomicInteger calls = new AtomicInteger();

        String stale = client.fetch(key, 120L, () -> {
            calls.incrementAndGet();
            return "new";
        });
        assertEquals("old", stale);

        assertTrue(awaitUntil(() -> "new".equals(client.rawGet(key)), 5000L),
                "异步回源后应写回新值，当前=" + client.rawGet(key));
        assertEquals(1, calls.get());

        String fresh = client.fetch(key, 120L, () -> {
            calls.incrementAndGet();
            return "another";
        });
        assertEquals("new", fresh);
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentFetchOnTaggedKeyShouldLoadOnlyOnce() throws Exception {
        final String key = "read:concurrent";
        client.rawSet(key, "old", 120L);
        tagDelete(key);

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(10);
        ExecutorService callers = Executors.newFixedThreadPool(10);
        try {
            for (int i = 0; i < 10; i++) {
                callers.execute(() -> {
                    try {
                        start.await();
                        client.fetch(key, 120L, () -> {
                            calls.incrementAndGet();
                            sleepQuietly(300L);
                            return "new";
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发 fetch 未在超时内完成");
            sleepQuietly(500L);
            assertEquals(1, calls.get(), "同一 JVM 内只应回源一次");
            assertEquals("new", client.rawGet(key));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void staleWriteBackShouldNotOverwriteNewValue() throws Exception {
        final String key = "read:race";
        client.rawSet(key, "old", 120L);
        tagDelete(key);

        final CountDownLatch loaderEntered = new CountDownLatch(1);
        final CountDownLatch releaseLoader = new CountDownLatch(1);
        final CountDownLatch taskCompleted = new CountDownLatch(1);
        Executor trackingExecutor = command -> asyncPool.execute(() -> {
            try {
                command.run();
            } finally {
                taskCompleted.countDown();
            }
        });
        RockscacheReadClient racingClient = new RockscacheReadClient(redis, properties, trackingExecutor);

        String returned = racingClient.fetch(key, 120L, () -> {
            loaderEntered.countDown();
            try {
                releaseLoader.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "stale";
        });
        assertEquals("old", returned);
        assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "异步回源未启动");

        racingClient.rawSet(key, "new", 120L);
        releaseLoader.countDown();
        assertTrue(taskCompleted.await(5, TimeUnit.SECONDS), "异步回源未完成");

        assertEquals("new", racingClient.rawGet(key), "旧 owner 的写回必须被拒绝");
    }

    @Test
    void emptyResultShouldUseEmptyExpire() {
        String key = "read:empty";
        String value = client.fetch(key, 120L, () -> "");

        assertEquals("", value);
        assertEquals("", client.rawGet(key));
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0 && ttl <= 60, "ttl=" + ttl);
    }

    private void tagDelete(String key) {
        redis.execute(RockscacheScripts.TAG_DELETE, Collections.singletonList(key), "10");
    }

    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(20L);
        }
        return condition.getAsBoolean();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
