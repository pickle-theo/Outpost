package com.outpost.sample;

import com.outpost.core.admin.OutpostAdminService;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.delivery.UnrecoverableDeliveryException;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.core.schedule.DistributedLock;
import com.outpost.core.schedule.ScheduleRoundExecutor;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import com.outpost.starter.scheduler.LocalDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用 Outbox 端到端：只启动 MySQL 容器，不启动 Redis 容器，验证「缓存关闭 + local 锁」
 * 的纯业务消息链路（事务回滚、注解注册、延迟消息、死信重发、并发 CAS）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {SampleApplication.class, OutpostGenericIT.GenericHandlers.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutpostGenericIT {

    private static final String UNRECOVERABLE_BIZ_TYPE = "it_unrecoverable_generic";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("outpost")
            .withUsername("outpost")
            .withPassword("outpost");

    @DynamicPropertySource
    static void outpostProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("outpost.enabled", () -> true);
        registry.add("outpost.auto-create-table", () -> true);
        registry.add("outpost.schedule.enabled", () -> false);
        registry.add("outpost.delivery-cache.enabled", () -> false);
        registry.add("outpost.schedule.lock-type", () -> "local");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcOutpostMessageRepository repository;
    @Autowired
    private OutpostTemplate template;
    @Autowired
    private OutpostAdminService adminService;
    @Autowired
    private ScheduleRoundExecutor roundExecutor;
    @Autowired
    private DeliveryHandlerRegistry handlerRegistry;
    @Autowired
    private DistributedLock lock;
    @Autowired
    private OrderPaidHandler orderPaidHandler;
    @Autowired
    private SampleOrderService orderService;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outpost_message");
        orderPaidHandler.clear();
    }

    @Test
    void startsWithoutRedisAndCacheHandlers() {
        assertTrue(lock instanceof LocalDistributedLock);
        assertFalse(handlerRegistry.bizTypes().contains("cache_delete"));
        assertFalse(handlerRegistry.bizTypes().contains("cache_tag_delete"));
        assertTrue(handlerRegistry.bizTypes().contains(OrderPaidHandler.BIZ_TYPE));
    }

    @Test
    void transactionRollbackLeavesNoMessage() {
        assertThrows(IllegalStateException.class, () -> template.executeInTransaction(() -> {
            template.registerMessage(OrderPaidHandler.BIZ_TYPE, "order:rollback", "payload");
            throw new IllegalStateException("business rollback");
        }));
        assertEquals(0L, count());
    }

    @Test
    void genericMessageIsDeliveredEndToEnd() throws Exception {
        template.executeInTransaction(() -> {
            template.registerMessage(OrderPaidHandler.BIZ_TYPE, "order:1", "payload-1");
            return null;
        });
        assertEquals(1L, count());

        roundExecutor.runOnce();
        awaitDelivered(1);
        assertEquals(Collections.singletonList("order:1"), orderPaidHandler.delivered());
        assertEquals(2, statusOf(firstId()));
    }

    @Test
    void annotationRegistersGenericMessage() {
        orderService.markPaid("order:2");

        assertEquals(1L, count());
        assertEquals(OrderPaidHandler.BIZ_TYPE, jdbc.queryForObject(
                "SELECT biz_type FROM outpost_message", String.class));
        assertEquals("order:2", jdbc.queryForObject(
                "SELECT biz_key FROM outpost_message", String.class));
    }

    @Test
    void delayedMessageIsNotDue() {
        template.executeInTransaction(() -> {
            template.registerDelayedMessage(OrderPaidHandler.BIZ_TYPE,
                    "order:delayed", "payload", 3600000L);
            return null;
        });
        assertTrue(repository.selectDue(100).isEmpty());
    }

    @Test
    void unrecoverableFailureGoesDeadAndCanBeResent() throws Exception {
        long id = insert(UNRECOVERABLE_BIZ_TYPE, "order:bad", 10);

        roundExecutor.runOnceDegraded();
        awaitStatus(id, 4);

        assertEquals(2, deadReasonOf(id));
        assertEquals(1L, repository.countDead());

        adminService.resendMessage(id);
        assertEquals(0, statusOf(id));
        assertEquals(0, retryCountOf(id));
        assertNull(jdbc.queryForObject(
                "SELECT dead_reason FROM outpost_message WHERE id = ?", Integer.class, id));
    }

    @Test
    void concurrentCasClaimDeliversEachMessageOnce() throws Exception {
        long first = insert(OrderPaidHandler.BIZ_TYPE, "concurrent:1", 10);
        long second = insert(OrderPaidHandler.BIZ_TYPE, "concurrent:2", 10);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                roundExecutor.runOnceDegraded();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        awaitDelivered(2);
        assertEquals(2, orderPaidHandler.delivered().size());
        assertEquals(1, deliverAttempt(first));
        assertEquals(1, deliverAttempt(second));
    }

    private long insert(String bizType, String bizKey, int maxRetry) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setBizType(bizType);
        record.setBizKey(bizKey);
        record.setPayload(bizKey);
        record.setMessageType(0);
        record.setMaxRetry(maxRetry);
        record.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
        repository.insertBatch(Collections.singletonList(record));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM outpost_message", Long.class);
        assertNotNull(id);
        return id;
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outpost_message", Long.class);
    }

    private long firstId() {
        return jdbc.queryForObject("SELECT MIN(id) FROM outpost_message", Long.class);
    }

    private int statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM outpost_message WHERE id = ?", Integer.class, id);
    }

    private int retryCountOf(long id) {
        return jdbc.queryForObject("SELECT retry_count FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private int deadReasonOf(long id) {
        return jdbc.queryForObject("SELECT dead_reason FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private int deliverAttempt(long id) {
        return jdbc.queryForObject("SELECT deliver_attempt FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private void awaitStatus(long id, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (statusOf(id) == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expected, statusOf(id));
    }

    private void awaitDelivered(int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (orderPaidHandler.delivered().size() >= expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expected, orderPaidHandler.delivered().size());
    }

    @TestConfiguration
    static class GenericHandlers {

        @Bean
        DeliveryHandler genericUnrecoverableHandler() {
            return new DeliveryHandler() {
                @Override
                public String getBizType() {
                    return UNRECOVERABLE_BIZ_TYPE;
                }

                @Override
                public void deliver(OutboxDeliveryMessage message) {
                    throw new UnrecoverableDeliveryException("bad payload");
                }
            };
        }
    }
}
