package com.outpost.core.schedule;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryDispatcher;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.OutpostMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮调度执行器：持锁模式走批量抢占，降级模式走逐条 CAS。
 *
 * <p>仓储异常向上抛出，由调度器记录日志；耗时指标始终在 finally 中记录。</p>
 */
public final class ScheduleRoundExecutor {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final OutpostMessageRepository repository;
    private final DeliveryDispatcher dispatcher;
    private final OutpostProperties properties;
    private final OutpostMetrics metrics;

    public ScheduleRoundExecutor(OutpostMessageRepository repository,
                                 DeliveryDispatcher dispatcher,
                                 OutpostProperties properties,
                                 OutpostMetrics metrics) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        if (dispatcher == null) {
            throw new IllegalArgumentException("dispatcher 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics 不能为 null");
        }
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 持锁模式单轮：超时重置 → 查询 → 批量抢占 → 影响行数不匹配时重查 → 提交。
     */
    public void runOnce() {
        long start = System.nanoTime();
        try {
            OutpostProperties.Schedule schedule = properties.getSchedule();
            repository.resetTimeoutProcessing(schedule.getProcessingTimeoutSeconds());

            List<OutboxMessageRecord> due = repository.selectDue(schedule.getBatchSize());
            if (due == null || due.isEmpty()) {
                return;
            }

            List<Long> ids = new ArrayList<Long>(due.size());
            for (OutboxMessageRecord record : due) {
                ids.add(record.getId());
            }

            int affected = repository.claimBatch(ids);
            List<OutboxMessageRecord> deliverable;
            if (affected != ids.size()) {
                deliverable = new ArrayList<OutboxMessageRecord>();
                List<OutboxMessageRecord> reloaded = repository.selectByIds(ids);
                if (reloaded != null) {
                    for (OutboxMessageRecord record : reloaded) {
                        if (record != null && record.getStatus() == MessageStatus.PROCESSING.code()) {
                            deliverable.add(record);
                        }
                    }
                }
            } else {
                deliverable = due;
            }
            dispatcher.dispatch(deliverable);
        } finally {
            metrics.onScheduleDuration(elapsedMillis(start));
        }
    }

    /**
     * 降级模式单轮：超时重置 → 查询 → 逐条 CAS 抢占 → 提交。
     */
    public void runOnceDegraded() {
        long start = System.nanoTime();
        try {
            OutpostProperties.Schedule schedule = properties.getSchedule();
            repository.resetTimeoutProcessing(schedule.getProcessingTimeoutSeconds());

            List<OutboxMessageRecord> due = repository.selectDue(schedule.getBatchSize());
            if (due == null || due.isEmpty()) {
                return;
            }

            List<OutboxMessageRecord> claimed = new ArrayList<OutboxMessageRecord>(due.size());
            for (OutboxMessageRecord record : due) {
                if (repository.claimOne(record.getId())) {
                    claimed.add(record);
                }
            }
            dispatcher.dispatch(claimed);
        } finally {
            metrics.onScheduleDuration(elapsedMillis(start));
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    }
}
