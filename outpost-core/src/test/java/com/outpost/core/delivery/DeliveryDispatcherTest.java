package com.outpost.core.delivery;

import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.MessageType;
import com.outpost.core.message.OutboxMessageRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryDispatcherTest {

    private final RecordingOutcomeHandler outcomeHandler = new RecordingOutcomeHandler();

    @Test
    void shouldContinueBatchWhenFirstMessageThrowsError() {
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(Arrays.<DeliveryHandler>asList(
                        new ThrowingHandler("boom"), new SucceedingHandler("ok"))),
                outcomeHandler);

        dispatcher.dispatch(Arrays.asList(record(1L, "boom", null), record(2L, "ok", null)));

        assertEquals(Collections.singletonList(2L), outcomeHandler.successIds);
        assertEquals(Collections.singletonList(1L), outcomeHandler.failureIds);
        assertEquals(1, outcomeHandler.errors.size());
        assertTrue(outcomeHandler.errors.get(0) instanceof AssertionError);
    }

    @Test
    void shouldTreatMissingHandlerAsFailureAndContinue() {
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(
                        Collections.<DeliveryHandler>singletonList(new SucceedingHandler("ok"))),
                outcomeHandler);

        dispatcher.dispatch(Arrays.asList(
                record(1L, "absent", null), record(2L, "ok", null)));

        assertEquals(Collections.singletonList(2L), outcomeHandler.successIds);
        assertEquals(Collections.singletonList(1L), outcomeHandler.failureIds);
        assertTrue(outcomeHandler.errors.get(0) instanceof IllegalStateException);
    }

    @Test
    void shouldPropagateTraceIdAndRestoreCallerMdc() {
        final List<String> seenTraceIds = new ArrayList<String>();
        DeliveryHandler handler = new DeliveryHandler() {
            @Override
            public String getBizType() {
                return "ok";
            }

            @Override
            public void deliver(OutboxDeliveryMessage message) {
                seenTraceIds.add(MDC.get("traceId"));
            }
        };
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(Collections.singletonList(handler)), outcomeHandler);

        MDC.put("traceId", "caller-trace");
        try {
            dispatcher.dispatch(Collections.singletonList(record(1L, "ok", "message-trace")));

            assertEquals(Collections.singletonList("message-trace"), seenTraceIds);
            assertEquals("caller-trace", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldClearTraceIdWhenRecordHasNone() {
        final List<String> seenTraceIds = new ArrayList<String>();
        DeliveryHandler handler = new DeliveryHandler() {
            @Override
            public String getBizType() {
                return "ok";
            }

            @Override
            public void deliver(OutboxDeliveryMessage message) {
                seenTraceIds.add(MDC.get("traceId"));
            }
        };
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(Collections.singletonList(handler)), outcomeHandler);

        MDC.put("traceId", "caller-trace");
        try {
            dispatcher.dispatch(Collections.singletonList(record(1L, "ok", null)));

            assertEquals(Collections.singletonList(null), seenTraceIds);
            assertEquals("caller-trace", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldNotSubmitEmptyBatch() {
        final AtomicInteger executions = new AtomicInteger();
        Executor countingExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                executions.incrementAndGet();
                command.run();
            }
        };
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(countingExecutor,
                new DeliveryHandlerRegistry(Collections.<DeliveryHandler>emptyList()),
                outcomeHandler);

        dispatcher.dispatch(Collections.<OutboxMessageRecord>emptyList());
        dispatcher.dispatch(null);

        assertEquals(0, executions.get());
        assertTrue(outcomeHandler.successIds.isEmpty());
        assertTrue(outcomeHandler.failureIds.isEmpty());
    }

    @Test
    void shouldReportFailureWhenMessageDtoCannotBeBuilt() {
        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(
                        Collections.<DeliveryHandler>singletonList(new SucceedingHandler("cache_delete"))),
                outcomeHandler);
        OutboxMessageRecord invalid = record(1L, "cache_delete", null);
        invalid.setBizKey(null);

        dispatcher.dispatch(Collections.singletonList(invalid));

        assertTrue(outcomeHandler.successIds.isEmpty());
        assertEquals(Collections.singletonList(1L), outcomeHandler.failureIds);
        assertTrue(outcomeHandler.errors.get(0) instanceof IllegalArgumentException);
    }

    private static OutboxMessageRecord record(long id, String bizType, String traceId) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType(bizType);
        record.setBizKey("key-" + id);
        record.setPayload("payload");
        record.setMessageType(MessageType.NORMAL.code());
        record.setStatus(MessageStatus.PROCESSING.code());
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setDeliverAttempt(1);
        record.setTraceId(traceId);
        return record;
    }

    private static final class SucceedingHandler implements DeliveryHandler {

        private final String bizType;

        SucceedingHandler(String bizType) {
            this.bizType = bizType;
        }

        @Override
        public String getBizType() {
            return bizType;
        }

        @Override
        public void deliver(OutboxDeliveryMessage message) {
            // no-op
        }
    }

    private static final class ThrowingHandler implements DeliveryHandler {

        private final String bizType;

        ThrowingHandler(String bizType) {
            this.bizType = bizType;
        }

        @Override
        public String getBizType() {
            return bizType;
        }

        @Override
        public void deliver(OutboxDeliveryMessage message) {
            throw new AssertionError("boom");
        }
    }

    private static final class RecordingOutcomeHandler implements DeliveryOutcomeHandler {

        private final List<Long> successIds = new ArrayList<Long>();
        private final List<Long> failureIds = new ArrayList<Long>();
        private final List<Throwable> errors = new ArrayList<Throwable>();

        @Override
        public void onSuccess(OutboxMessageRecord record, long durationMs) {
            successIds.add(record.getId());
        }

        @Override
        public void onFailure(OutboxMessageRecord record, Throwable error, long durationMs) {
            failureIds.add(record.getId());
            errors.add(error);
        }
    }
}
