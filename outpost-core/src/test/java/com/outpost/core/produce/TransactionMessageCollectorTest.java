package com.outpost.core.produce;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.MessageType;
import com.outpost.core.message.OutboxMessageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionMessageCollectorTest {

    private DataSource dataSource;
    private OutpostProperties properties;
    private TransactionTestSupport.RecordingRepository repository;
    private TransactionMessageCollector collector;

    @BeforeEach
    void setUp() {
        dataSource = TransactionTestSupport.newDataSource();
        properties = new OutpostProperties();
        repository = new TransactionTestSupport.RecordingRepository();
        collector = new TransactionMessageCollector(repository, dataSource, properties);
    }

    @AfterEach
    void tearDown() {
        TransactionTestSupport.reset();
    }

    @Test
    void shouldThrowEveryTimeWithoutTransactionContext() {
        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> collector.register(normal("order", "o-1")));
        IllegalStateException second = assertThrows(IllegalStateException.class,
                () -> collector.register(normal("order", "o-2")));

        assertTrue(first.getMessage().contains("事务"));
        assertTrue(second.getMessage().contains("事务"));
        assertTrue(repository.batches.isEmpty());
    }

    @Test
    void shouldNotRegisterWhenDataSourceResourceNotBound() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
                () -> collector.register(normal("order", "o-1")));
        assertTrue(repository.batches.isEmpty());
    }

    @Test
    void shouldInsertOnBeforeCommitWithExpectedFields() {
        TransactionTestSupport.begin(dataSource);
        collector.register(MessageRegistration.normal("order", "o-1", "{\"id\":1}", 0L, 3));

        assertTrue(repository.batches.isEmpty());

        TransactionTestSupport.triggerBeforeCommit();

        assertEquals(1, repository.batches.size());
        OutboxMessageRecord record = repository.batches.get(0).get(0);
        assertEquals("order", record.getBizType());
        assertEquals("o-1", record.getBizKey());
        assertEquals("{\"id\":1}", record.getPayload());
        assertEquals(MessageType.NORMAL.code(), record.getMessageType());
        assertEquals(MessageStatus.PENDING.code(), record.getStatus());
        assertEquals(0, record.getRetryCount());
        assertEquals(3, record.getMaxRetry());
        assertEquals(0, record.getDeliverAttempt());
        assertEquals(0, record.getVersion());
        assertNull(record.getTraceId());
        assertTrue(record.getNextRetryTime().isAfter(LocalDateTime.now().minusSeconds(5)));
    }

    @Test
    void shouldInsertDelayedMessageWithFutureNextRetryTime() {
        TransactionTestSupport.begin(dataSource);
        collector.register(MessageRegistration.normal("order", "o-1", null, 2000L, 5));

        TransactionTestSupport.triggerBeforeCommit();

        OutboxMessageRecord record = repository.batches.get(0).get(0);
        assertTrue(record.getNextRetryTime().isAfter(LocalDateTime.now().plusNanos(1_500_000_000L)));
    }

    @Test
    void shouldClearScopeAfterCompletionAndNotInsertTwice() {
        TransactionTestSupport.begin(dataSource);
        collector.register(normal("order", "o-1"));
        TransactionTestSupport.triggerBeforeCommit();
        assertEquals(1, repository.batches.size());

        TransactionSynchronization scope = TransactionTestSupport.snapshot().get(0);
        TransactionTestSupport.complete(dataSource, TransactionSynchronization.STATUS_COMMITTED);

        scope.beforeCommit(false);
        assertEquals(1, repository.batches.size());
    }

    @Test
    void shouldRejectOversizedPayloadWithoutInsert() {
        properties.setMaxPayloadBytes(4);
        TransactionTestSupport.begin(dataSource);
        collector.register(normal("order", "o-1", "12345"));

        assertThrows(IllegalArgumentException.class, TransactionTestSupport::triggerBeforeCommit);
        assertTrue(repository.batches.isEmpty());
    }

    @Test
    void shouldInsertInBatchesWhenOverThreshold() {
        properties.setBatchRegisterThreshold(2);
        TransactionTestSupport.begin(dataSource);
        for (int i = 0; i < 5; i++) {
            collector.register(normal("order", "o-" + i));
        }

        TransactionTestSupport.triggerBeforeCommit();

        assertEquals(3, repository.batches.size());
        assertEquals(2, repository.batches.get(0).size());
        assertEquals(2, repository.batches.get(1).size());
        assertEquals(1, repository.batches.get(2).size());

        List<String> bizKeys = new ArrayList<String>();
        for (OutboxMessageRecord record : repository.allRecords()) {
            bizKeys.add(record.getBizKey());
        }
        assertEquals(Arrays.asList("o-0", "o-1", "o-2", "o-3", "o-4"), bizKeys);
    }

    @Test
    void shouldRegisterAgainAfterRollbackWithoutThreadLocalLeak() {
        TransactionTestSupport.begin(dataSource);
        collector.register(normal("order", "rolled-back"));
        TransactionTestSupport.complete(dataSource, TransactionSynchronization.STATUS_ROLLED_BACK);

        assertTrue(repository.batches.isEmpty());

        TransactionTestSupport.begin(dataSource);
        collector.register(normal("order", "after-rollback"));
        TransactionTestSupport.triggerBeforeCommit();

        assertEquals(1, repository.batches.size());
        assertEquals(1, repository.batches.get(0).size());
        assertEquals("after-rollback", repository.batches.get(0).get(0).getBizKey());
    }

    @Test
    void shouldIsolateRequiresNewScope() {
        TransactionTestSupport.begin(dataSource);
        collector.register(normal("outer", "outer-1"));
        List<TransactionSynchronization> outerSynchronizations = TransactionTestSupport.snapshot();

        // 模拟 REQUIRES_NEW：外层事务同步被挂起
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        collector.register(normal("inner", "inner-1"));
        TransactionTestSupport.triggerBeforeCommit();

        assertEquals(1, repository.batches.size());
        assertEquals("inner-1", repository.batches.get(0).get(0).getBizKey());

        // 内层回滚，不应影响外层已注册消息
        TransactionTestSupport.complete(dataSource, TransactionSynchronization.STATUS_ROLLED_BACK);

        // 模拟恢复外层事务：重新注册原 synchronization 实例
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new Object());
        for (TransactionSynchronization synchronization : outerSynchronizations) {
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }

        collector.register(normal("outer", "outer-2"));
        TransactionTestSupport.triggerBeforeCommit();

        assertEquals(2, repository.batches.size());
        List<OutboxMessageRecord> outerBatch = repository.batches.get(1);
        assertEquals(2, outerBatch.size());
        assertEquals("outer-1", outerBatch.get(0).getBizKey());
        assertEquals("outer-2", outerBatch.get(1).getBizKey());
    }

    private static MessageRegistration normal(String bizType, String bizKey) {
        return MessageRegistration.normal(bizType, bizKey, null, 0L, 10);
    }

    private static MessageRegistration normal(String bizType, String bizKey, String payload) {
        return MessageRegistration.normal(bizType, bizKey, payload, 0L, 10);
    }
}
