package com.outpost.core.delivery;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.MessageType;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.retry.DefaultBackoffPolicy;
import com.outpost.core.support.InMemoryOutpostMessageRepository;
import com.outpost.core.support.RecordingOutpostMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDeliveryOutcomeHandlerTest {

    private InMemoryOutpostMessageRepository repository;
    private RecordingDeadMessageListener listener;
    private RecordingOutpostMetrics metrics;
    private DefaultDeliveryOutcomeHandler handler;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutpostMessageRepository();
        listener = new RecordingDeadMessageListener();
        metrics = new RecordingOutpostMetrics();
        DeadMessageNotifier notifier = new DeadMessageNotifier(
                Collections.<DeadMessageListener>singletonList(listener));
        handler = new DefaultDeliveryOutcomeHandler(repository, notifier,
                new DefaultBackoffPolicy(new long[]{1L, 3L, 10L}), metrics);
    }

    @Test
    void shouldMarkSuccess() {
        handler.onSuccess(record(1L, 0, 3), 12L);

        assertEquals(1, repository.successBatches.size());
        assertEquals(Collections.singletonList(1L), repository.successBatches.get(0));
        assertEquals(1, metrics.deliverySuccess.get());
        assertEquals(0, listener.count);
    }

    @Test
    void shouldMarkDeadForDirectUnrecoverable() {
        handler.onFailure(record(2L, 2, 5),
                new UnrecoverableDeliveryException("payload 非法"), 7L);

        assertEquals(1, repository.deadCalls.size());
        InMemoryOutpostMessageRepository.DeadCall call = repository.deadCalls.get(0);
        assertEquals(2L, call.id);
        assertEquals(2, call.newRetryCount);
        assertEquals(DeadReason.UNRECOVERABLE, call.reason);
        assertEquals("UnrecoverableDeliveryException: payload 非法", call.errorMsg);
        assertEquals(1, listener.count);
        assertEquals(DeadReason.UNRECOVERABLE, listener.reasons.get(0));
        assertEquals(1, metrics.deliveryFail.get());
        assertEquals(1, metrics.dead.get());
        assertEquals(DeadReason.UNRECOVERABLE, metrics.deadReasons.get(0));
        assertTrue(repository.failedCalls.isEmpty());
    }

    @Test
    void shouldMarkDeadForWrappedUnrecoverable() {
        RuntimeException error = new RuntimeException("包装异常",
                new IllegalStateException("中间层",
                        new UnrecoverableDeliveryException("格式非法")));

        handler.onFailure(record(3L, 4, 10), error, 9L);

        assertEquals(1, repository.deadCalls.size());
        assertEquals(DeadReason.UNRECOVERABLE, repository.deadCalls.get(0).reason);
        assertEquals(4, repository.deadCalls.get(0).newRetryCount);
        assertEquals(1, listener.count);
        assertEquals(1, metrics.dead.get());
    }

    @Test
    void shouldMarkFailedBelowMaxRetry() {
        handler.onFailure(record(4L, 0, 3), new IllegalStateException("redis 超时"), 5L);

        assertEquals(1, repository.failedCalls.size());
        InMemoryOutpostMessageRepository.FailedCall call = repository.failedCalls.get(0);
        assertEquals(4L, call.id);
        assertEquals(1, call.newRetryCount);
        assertEquals(1L, call.backoffSeconds);
        assertEquals("IllegalStateException: redis 超时", call.errorMsg);
        assertEquals(1, metrics.deliveryFail.get());
        assertEquals(1, metrics.retry.get());
        assertEquals(1, metrics.retryCounts.get(0).intValue());
        assertEquals(0, metrics.dead.get());
        assertEquals(0, listener.count);
        assertTrue(repository.deadCalls.isEmpty());
    }

    @Test
    void shouldMarkDeadWhenRetryExhausted() {
        handler.onFailure(record(5L, 2, 3), new IllegalStateException("redis 超时"), 5L);

        assertEquals(1, repository.deadCalls.size());
        InMemoryOutpostMessageRepository.DeadCall call = repository.deadCalls.get(0);
        assertEquals(5L, call.id);
        assertEquals(3, call.newRetryCount);
        assertEquals(DeadReason.RETRY_EXHAUSTED, call.reason);
        assertEquals(1, metrics.dead.get());
        assertEquals(DeadReason.RETRY_EXHAUSTED, metrics.deadReasons.get(0));
        assertEquals(1, listener.count);
        assertEquals(0, metrics.retry.get());
        assertTrue(repository.failedCalls.isEmpty());
    }

    @Test
    void shouldStopAtFailedForAuxiliaryMessage() {
        OutboxMessageRecord record = record(6L, 0, 1);
        record.setMessageType(MessageType.AUXILIARY.code());

        handler.onFailure(record, new IllegalStateException("redis 超时"), 5L);

        assertEquals(1, repository.failedCalls.size());
        assertEquals(1, repository.failedCalls.get(0).newRetryCount);
        assertEquals(0, repository.deadCalls.size());
        assertEquals(0, listener.count);
        assertEquals(0, metrics.dead.get());
        assertEquals(1, metrics.deliveryFail.get());
        assertEquals(0, metrics.retry.get());
    }

    @Test
    void shouldTruncateLongErrorMessage() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            builder.append('x');
        }
        handler.onFailure(record(7L, 0, 3), new RuntimeException(builder.toString()), 5L);

        String errorMsg = repository.failedCalls.get(0).errorMsg;
        assertEquals(1024, errorMsg.length());
        assertTrue(errorMsg.startsWith("RuntimeException: xxx"));
    }

    @Test
    void shouldUseClassNameWhenMessageIsNull() {
        handler.onFailure(record(8L, 0, 3), new RuntimeException(), 5L);

        assertEquals("RuntimeException", repository.failedCalls.get(0).errorMsg);
    }

    @Test
    void shouldContinueMetricsWhenSuccessWriteBackFails() {
        repository.writeError = new IllegalStateException("db down");

        handler.onSuccess(record(9L, 0, 3), 5L);

        assertEquals(1, metrics.deliverySuccess.get());
    }

    @Test
    void shouldContinueMetricsAndNotifierWhenWriteBackFails() {
        repository.writeError = new IllegalStateException("db down");

        handler.onFailure(record(10L, 2, 3), new IllegalStateException("redis 超时"), 5L);

        assertEquals(1, metrics.deliveryFail.get());
        assertEquals(1, metrics.dead.get());
        assertEquals(1, listener.count);
    }

    @Test
    void shouldUseFallbackSummaryWhenErrorIsNull() {
        handler.onFailure(record(11L, 0, 3), null, 5L);

        assertEquals("UnknownError", repository.failedCalls.get(0).errorMsg);
    }

    private static OutboxMessageRecord record(long id, int retryCount, int maxRetry) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType("cache_delete");
        record.setBizKey("key-" + id);
        record.setPayload("payload");
        record.setMessageType(MessageType.NORMAL.code());
        record.setStatus(MessageStatus.PROCESSING.code());
        record.setRetryCount(retryCount);
        record.setMaxRetry(maxRetry);
        record.setDeliverAttempt(retryCount + 1);
        return record;
    }

    private static final class RecordingDeadMessageListener implements DeadMessageListener {

        private final List<DeadReason> reasons = new ArrayList<DeadReason>();
        private int count;

        @Override
        public void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
            count++;
            reasons.add(reason);
        }
    }
}
