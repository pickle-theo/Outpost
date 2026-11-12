package com.outpost.core.schedule;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryDispatcher;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.DeliveryOutcomeHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.support.InMemoryOutpostMessageRepository;
import com.outpost.core.support.RecordingOutpostMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleRoundExecutorTest {

    private InMemoryOutpostMessageRepository repository;
    private RecordingOutpostMetrics metrics;
    private RecordingOutcomeHandler outcomeHandler;
    private OutpostProperties properties;
    private ScheduleRoundExecutor roundExecutor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutpostMessageRepository();
        metrics = new RecordingOutpostMetrics();
        outcomeHandler = new RecordingOutcomeHandler();
        properties = new OutpostProperties();

        DeliveryHandler handler = new DeliveryHandler() {
            @Override
            public String getBizType() {
                return "cache_delete";
            }

            @Override
            public void deliver(OutboxDeliveryMessage message) {
                // no-op
            }
        };
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(Collections.singletonList(handler)), outcomeHandler);
        roundExecutor = new ScheduleRoundExecutor(repository, dispatcher, properties, metrics);
    }

    @Test
    void shouldDispatchOnlyProcessingRecordsWhenClaimCountMismatches() {
        OutboxMessageRecord first = record(1L, MessageStatus.PROCESSING);
        OutboxMessageRecord second = record(2L, MessageStatus.FAILED);
        repository.due = Arrays.asList(first, second);
        repository.claimBatchAffected = 1;
        Map<Long, OutboxMessageRecord> byId = new LinkedHashMap<Long, OutboxMessageRecord>();
        byId.put(1L, first);
        byId.put(2L, second);
        repository.byId = byId;

        roundExecutor.runOnce();

        assertEquals(Collections.singletonList(1L), outcomeHandler.successIds);
        assertEquals(1, repository.claimBatchCalls.size());
        assertEquals(Arrays.asList(1L, 2L), repository.claimBatchCalls.get(0));
        assertEquals(Collections.singletonList(300), repository.resetTimeoutCalls);
        assertEquals(1, metrics.scheduleDurationCount.get());
        assertTrue(metrics.lastScheduleDurationMs.get() >= 0L);
    }

    @Test
    void shouldDispatchWholeBatchWhenAllClaimed() {
        repository.due = Arrays.asList(
                record(1L, MessageStatus.PENDING), record(2L, MessageStatus.PENDING));

        roundExecutor.runOnce();

        assertEquals(Arrays.asList(1L, 2L), outcomeHandler.successIds);
        assertEquals(1, repository.claimBatchCalls.size());
        assertEquals(2, repository.claimBatchCalls.get(0).size());
        assertEquals(1, metrics.scheduleDurationCount.get());
    }

    @Test
    void shouldSkipDispatchWhenNoDueMessage() {
        repository.due = Collections.emptyList();

        roundExecutor.runOnce();

        assertTrue(outcomeHandler.successIds.isEmpty());
        assertTrue(repository.claimBatchCalls.isEmpty());
        assertEquals(1, repository.resetTimeoutCalls.size());
        assertEquals(1, metrics.scheduleDurationCount.get());
    }

    @Test
    void shouldClaimOneByOneInDegradedMode() {
        repository.due = Arrays.asList(
                record(1L, MessageStatus.FAILED), record(2L, MessageStatus.FAILED));
        repository.claimableIds = Collections.singleton(2L);

        roundExecutor.runOnceDegraded();

        assertEquals(Arrays.asList(1L, 2L), repository.claimOneCalls);
        assertEquals(Collections.singletonList(2L), outcomeHandler.successIds);
        assertTrue(repository.claimBatchCalls.isEmpty());
        assertEquals(1, metrics.scheduleDurationCount.get());
    }

    @Test
    void shouldPropagateRepositoryErrorButRecordMetrics() {
        repository.selectError = new IllegalStateException("db down");

        assertThrows(IllegalStateException.class, roundExecutor::runOnce);
        assertThrows(IllegalStateException.class, roundExecutor::runOnceDegraded);

        assertEquals(2, metrics.scheduleDurationCount.get());
    }

    private static OutboxMessageRecord record(long id, MessageStatus status) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType("cache_delete");
        record.setBizKey("key-" + id);
        record.setPayload("payload");
        record.setStatus(status.code());
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setDeliverAttempt(1);
        return record;
    }

    private static final class RecordingOutcomeHandler implements DeliveryOutcomeHandler {

        private final List<Long> successIds = new ArrayList<Long>();

        @Override
        public void onSuccess(OutboxMessageRecord record, long durationMs) {
            successIds.add(record.getId());
        }

        @Override
        public void onFailure(OutboxMessageRecord record, Throwable error, long durationMs) {
            throw new IllegalStateException("调度单测不应出现投递失败: " + error, error);
        }
    }
}
