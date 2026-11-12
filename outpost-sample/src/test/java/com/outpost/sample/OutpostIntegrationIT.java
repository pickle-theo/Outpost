package com.outpost.sample;

import com.outpost.core.admin.OutpostAdminService;
import com.outpost.core.cleanup.MessageCleanupTask;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryDispatcher;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.DeliveryOutcomeHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.delivery.UnrecoverableDeliveryException;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.monitor.NoopOutpostMetrics;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.core.schedule.ScheduleRoundExecutor;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 8 + Redis 7 集成测试：覆盖事务回滚、并发抢占、宕机恢复、
 * 超时重置、批量抢占不匹配、双删、死信重发、清理 8 类核心正确性场景。
 *
 * <p>Docker 不可用时由 {@code @Testcontainers(disabledWithoutDocker = true)} 整类跳过，
 * 不会导致构建失败。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {SampleApplication.class, OutpostIntegrationIT.FailingHandlers.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutpostIntegrationIT {

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
        registry.add("outpost.schedule.fixed-delay-ms", () -> 200L);
        registry.add("outpost.delivery-cache.handler-type", () -> "cache_delete");
        registry.add("outpost.delivery-cache.double-delete.enabled", () -> true);
        registry.add("outpost.delivery-cache.double-delete.delay-millis", () -> 50L);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcOutpostMessageRepository repository;
    @Autowired
    private OutpostTemplate template;
    @Autowired
    private ScheduleRoundExecutor roundExecutor;
    @Autowired
    private OutpostAdminService adminService;
    @Autowired
    private MessageCleanupTask cleanupTask;
    @Autowired
    private OutpostProperties properties;
    @Autowired
    private DeliveryHandlerRegistry registry;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM outpost_message");
    }

    @Test
    void rollbackLeavesNoMessage() {
        long before = count();
        assertThrows(IllegalStateException.class, () -> template.executeInTransaction(() -> {
            template.registerMessage("cache_delete", "order:rollback", "order:rollback");
            throw new IllegalStateException("business rollback");
        }));
        assertEquals(before, count());
    }

    @Test
    void concurrentCasClaimDeliversEachMessageOnce() throws Exception {
        long first = insert("cache_delete", "concurrent:1", 0, 10);
        long second = insert("cache_delete", "concurrent:2", 0, 10);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                roundExecutor.runOnceDegraded();
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        awaitStatus(first, 2);
        awaitStatus(second, 2);
        assertEquals(1, deliverAttempt(first));
        assertEquals(1, deliverAttempt(second));
    }

    @Test
    void timedOutProcessingMessageIsResetAndRedelivered() throws Exception {
        long id = insert("cache_delete", "recovery:1", 0, 10);
        jdbc.update("UPDATE outpost_message SET status = 1,"
                + " update_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE id = ?", id);

        assertEquals(1, repository.resetTimeoutProcessing(1));
        roundExecutor.runOnceDegraded();
        awaitStatus(id, 2);
        assertEquals(1, deliverAttempt(id));
    }

    @Test
    void redeliveryIsObservableThroughDeliverAttempt() throws Exception {
        long id = insert("cache_delete", "redelivery:1", 0, 10);
        jdbc.update("UPDATE outpost_message SET status = 1, deliver_attempt = 1,"
                + " update_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE id = ?", id);

        repository.resetTimeoutProcessing(1);
        roundExecutor.runOnceDegraded();
        awaitStatus(id, 2);

        assertEquals(2, deliverAttempt(id));
        assertTrue(repository.countRedelivery() >= 1L);
        assertTrue(adminService.getStats().getRedeliveryRatio() > 0D);
    }

    @Test
    void claimAffectedRowMismatchOnlyDispatchesProcessingRows() {
        insert("cache_delete", "mismatch:1", 0, 10);
        JdbcOutpostMessageRepository spy = Mockito.spy(repository);
        Mockito.doReturn(0).when(spy).claimBatch(Mockito.anyList());

        final List<Long> dispatched = new ArrayList<>();
        DeliveryDispatcher recordingDispatcher = new DeliveryDispatcher(
                Runnable::run, registry, new DeliveryOutcomeHandler() {
            @Override
            public void onSuccess(OutboxMessageRecord record, long durationMs) {
                dispatched.add(record.getId());
            }

            @Override
            public void onFailure(OutboxMessageRecord record, Throwable error, long durationMs) {
                dispatched.add(record.getId());
            }
        });
        ScheduleRoundExecutor executor = new ScheduleRoundExecutor(
                spy, recordingDispatcher, properties, NoopOutpostMetrics.INSTANCE);

        executor.runOnce();

        assertTrue(dispatched.isEmpty(), "影响行数不匹配时重查，PENDING 行不应被投递");
    }

    @Test
    void doubleDeleteRegistersTwoMessagesAndAuxiliaryStopsAtFailed() throws Exception {
        template.executeInTransaction(() -> {
            template.registerCacheInvalidate("order",
                    Collections.singletonList("double:delete:key"));
            return null;
        });

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM outpost_message WHERE biz_key = ? ORDER BY message_type",
                "order:double:delete:key");
        assertEquals(2, rows.size());
        assertEquals(0, ((Number) rows.get(0).get("message_type")).intValue());
        assertEquals(1, ((Number) rows.get(1).get("message_type")).intValue());
        assertEquals(1, ((Number) rows.get(1).get("max_retry")).intValue());
        assertNotNull(rows.get(1).get("next_retry_time"));

        // 让辅助消息投递失败，验证停在 FAILED 终态、不进死信、不再被扫到
        jdbc.update("UPDATE outpost_message SET payload = '' WHERE message_type = 1");
        long auxiliaryId = ((Number) rows.get(1).get("id")).longValue();
        roundExecutor.runOnceDegraded();
        awaitStatus(auxiliaryId, 3);

        assertEquals(1, retryCount(auxiliaryId));
        assertTrue(repository.selectDue(100).stream()
                .noneMatch(record -> record.getId().equals(auxiliaryId)));
        assertEquals(0L, repository.countDead());
    }

    @Test
    void deadLetterPathsAndResend() throws Exception {
        long exhausted = insert("it_fail", "dead:1", 0, 1);
        long unrecoverable = insert("it_unrecoverable", "dead:2", 0, 1);

        roundExecutor.runOnceDegraded();
        awaitStatus(exhausted, 4);
        awaitStatus(unrecoverable, 4);

        assertEquals(1, deadReason(exhausted));
        assertEquals(2, deadReason(unrecoverable));
        assertEquals(2L, repository.countDead());

        adminService.resendMessage(exhausted);
        assertEquals(0, statusOf(exhausted));
        assertEquals(0, retryCount(exhausted));
        assertNull(jdbc.queryForObject(
                "SELECT error_msg FROM outpost_message WHERE id = ?", String.class, exhausted));
        assertNull(jdbc.queryForObject(
                "SELECT dead_reason FROM outpost_message WHERE id = ?", Integer.class, exhausted));
    }

    @Test
    void cleanupKeepsFailedAndProcessingRows() {
        long success = insert("cache_delete", "cleanup:success", 0, 10);
        long dead = insert("cache_delete", "cleanup:dead", 0, 10);
        long failed = insert("cache_delete", "cleanup:failed", 0, 10);
        long processing = insert("cache_delete", "cleanup:processing", 0, 10);
        jdbc.update("UPDATE outpost_message SET status = 2 WHERE id = ?", success);
        jdbc.update("UPDATE outpost_message SET status = 4, dead_reason = 1 WHERE id = ?", dead);
        jdbc.update("UPDATE outpost_message SET status = 3 WHERE id = ?", failed);
        jdbc.update("UPDATE outpost_message SET status = 1 WHERE id = ?", processing);
        jdbc.update("UPDATE outpost_message SET update_time = DATE_SUB(NOW(), INTERVAL 40 DAY)");

        assertTrue(cleanupTask.runOnce() >= 2);
        assertEquals(2L, count());
        assertEquals(3, statusOf(failed));
        assertEquals(1, statusOf(processing));
    }

    private long insert(String bizType, String payload, int messageType, int maxRetry) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setBizType(bizType);
        record.setBizKey(bizType + ":" + payload);
        record.setPayload(payload);
        record.setMessageType(messageType);
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

    private int statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private int deliverAttempt(long id) {
        return jdbc.queryForObject("SELECT deliver_attempt FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private int retryCount(long id) {
        return jdbc.queryForObject("SELECT retry_count FROM outpost_message WHERE id = ?",
                Integer.class, id);
    }

    private int deadReason(long id) {
        return jdbc.queryForObject("SELECT dead_reason FROM outpost_message WHERE id = ?",
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

    @TestConfiguration
    static class FailingHandlers {

        @Bean
        DeliveryHandler itFailHandler() {
            return new DeliveryHandler() {
                @Override
                public String getBizType() {
                    return "it_fail";
                }

                @Override
                public void deliver(OutboxDeliveryMessage message) {
                    throw new IllegalStateException("always fails");
                }
            };
        }

        @Bean
        DeliveryHandler itUnrecoverableHandler() {
            return new DeliveryHandler() {
                @Override
                public String getBizType() {
                    return "it_unrecoverable";
                }

                @Override
                public void deliver(OutboxDeliveryMessage message) throws Exception {
                    throw new UnrecoverableDeliveryException("unrecoverable payload");
                }
            };
        }
    }
}
