package com.outpost.sample;

import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.starter.cache.RockscacheReadClient;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tag 模式端到端：TagAsDeleted → 返回旧值 → 异步回源刷新 → 新值可见，
 * 并验证双删在 tag 模式下被强制关闭、每个 key 只注册一条消息。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutpostTagModeIT {

    private static final String CACHE_KEY = "tag:e2e:1";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("outpost")
            .withUsername("outpost")
            .withPassword("outpost");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void outpostProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("outpost.enabled", () -> true);
        registry.add("outpost.auto-create-table", () -> true);
        registry.add("outpost.schedule.enabled", () -> false);
        registry.add("outpost.delivery-cache.handler-type", () -> "cache_tag_delete");
        registry.add("outpost.delivery-cache.tag.read-client", () -> "builtin");
        registry.add("outpost.delivery-cache.tag.delay-seconds", () -> 10);
        // 故意开启双删，验证 tag 模式启动期强制关闭
        registry.add("outpost.delivery-cache.double-delete.enabled", () -> true);
    }

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcOutpostMessageRepository repository;
    @Autowired
    private OutpostTemplate template;
    @Autowired
    private DeliveryHandlerRegistry handlerRegistry;
    @Autowired
    private RockscacheReadClient readClient;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outpost_message");
        redis.delete(CACHE_KEY);
    }

    @Test
    void tagAsDeletedThenFetchReturnsOldValueAndRefreshesToNew() throws Exception {
        redis.opsForHash().put(CACHE_KEY, "value", "old");
        redis.opsForHash().put(CACHE_KEY, "lockUntil",
                String.valueOf(System.currentTimeMillis() / 1000L + 60L));
        redis.expire(CACHE_KEY, 60L, TimeUnit.SECONDS);

        template.executeInTransaction(() -> {
            template.registerCacheInvalidate("order", Collections.singletonList(CACHE_KEY));
            return null;
        });

        // tag 模式双删被强制关闭，每个 key 只写一条消息
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outpost_message", Long.class)
                .longValue());
        Long messageId = jdbc.queryForObject("SELECT MIN(id) FROM outpost_message", Long.class);
        assertNotNull(messageId);
        OutboxMessageRecord record =
                repository.selectByIds(Collections.singletonList(messageId)).get(0);
        assertEquals("cache_tag_delete", record.getBizType());
        assertEquals(CACHE_KEY, record.getPayload());

        DeliveryHandler handler = handlerRegistry.getRequired("cache_tag_delete");
        handler.deliver(OutboxDeliveryMessage.from(record));

        Map<Object, Object> fields = redis.opsForHash().entries(CACHE_KEY);
        assertEquals("0", String.valueOf(fields.get("lockUntil")));
        assertFalse(fields.containsKey("lockOwner"));
        long ttl = redis.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        assertTrue(ttl > 0L && ttl <= 10L, "TTL 应约等于 tag.delay-seconds，实际=" + ttl);

        String firstRead = readClient.fetch(CACHE_KEY, 60L, () -> "new");
        assertEquals("old", firstRead);
        awaitNewValue();
    }

    private void awaitNewValue() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if ("new".equals(readClient.rawGet(CACHE_KEY))) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals("new", readClient.rawGet(CACHE_KEY));
    }
}
