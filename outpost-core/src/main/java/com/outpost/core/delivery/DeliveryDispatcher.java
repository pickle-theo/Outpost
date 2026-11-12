package com.outpost.core.delivery;

import com.outpost.core.message.OutboxMessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 投递分发器：整批作为一个任务提交到外部线程池，批内逐条隔离。
 *
 * <p>线程池由装配阶段提供，本类不创建也不管理线程池。</p>
 */
public final class DeliveryDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private static final String TRACE_ID_KEY = "traceId";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Executor executor;
    private final DeliveryHandlerRegistry registry;
    private final DeliveryOutcomeHandler outcomeHandler;

    public DeliveryDispatcher(Executor executor,
                              DeliveryHandlerRegistry registry,
                              DeliveryOutcomeHandler outcomeHandler) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为 null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为 null");
        }
        if (outcomeHandler == null) {
            throw new IllegalArgumentException("outcomeHandler 不能为 null");
        }
        this.executor = executor;
        this.registry = registry;
        this.outcomeHandler = outcomeHandler;
    }

    public void dispatch(List<OutboxMessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        final List<OutboxMessageRecord> batch = new ArrayList<OutboxMessageRecord>(records);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (OutboxMessageRecord record : batch) {
                        try {
                            dispatchOne(record);
                        } catch (Throwable t) {
                            LOG.error("Outpost 投递结果处理异常，messageId={}",
                                    record == null ? null : record.getId(), t);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("Outpost 投递批次执行异常，已隔离", t);
                }
            }
        });
    }

    private void dispatchOne(OutboxMessageRecord record) {
        long start = System.nanoTime();
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        try {
            if (record.getTraceId() != null && !record.getTraceId().trim().isEmpty()) {
                MDC.put(TRACE_ID_KEY, record.getTraceId());
            } else {
                MDC.remove(TRACE_ID_KEY);
            }

            try {
                OutboxDeliveryMessage message = OutboxDeliveryMessage.from(record);
                DeliveryHandler handler = registry.getRequired(record.getBizType());
                handler.deliver(message);
            } catch (Throwable t) {
                outcomeHandler.onFailure(record, t, elapsedMillis(start));
                return;
            }
            outcomeHandler.onSuccess(record, elapsedMillis(start));
        } finally {
            if (previousTraceId == null) {
                MDC.remove(TRACE_ID_KEY);
            } else {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            }
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    }
}
