package com.outpost.core.delivery;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.core.retry.BackoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * 默认投递结果处理器。
 *
 * <p>状态回写分支顺序固定：不可恢复 → 辅助消息 → 普通重试/耗尽。
 * 状态回写本身异常不会向外抛出，避免中断调度线程。</p>
 */
public final class DefaultDeliveryOutcomeHandler implements DeliveryOutcomeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDeliveryOutcomeHandler.class);

    private static final int MAX_ERROR_MSG_LENGTH = 1024;

    private final OutpostMessageRepository repository;
    private final DeadMessageNotifier deadNotifier;
    private final BackoffPolicy backoffPolicy;
    private final OutpostMetrics metrics;

    public DefaultDeliveryOutcomeHandler(OutpostMessageRepository repository,
                                         DeadMessageNotifier deadNotifier,
                                         BackoffPolicy backoffPolicy,
                                         OutpostMetrics metrics) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        if (deadNotifier == null) {
            throw new IllegalArgumentException("deadNotifier 不能为 null");
        }
        if (backoffPolicy == null) {
            throw new IllegalArgumentException("backoffPolicy 不能为 null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics 不能为 null");
        }
        this.repository = repository;
        this.deadNotifier = deadNotifier;
        this.backoffPolicy = backoffPolicy;
        this.metrics = metrics;
    }

    @Override
    public void onSuccess(OutboxMessageRecord record, long durationMs) {
        try {
            repository.markSuccessBatch(Collections.singletonList(record.getId()));
        } catch (Throwable t) {
            LOG.error("Outpost 回写投递成功状态失败，messageId={}, bizType={}",
                    record.getId(), record.getBizType(), t);
        }
        metrics.onDeliverySuccess(record.getBizType(), durationMs);
    }

    @Override
    public void onFailure(OutboxMessageRecord record, Throwable error, long durationMs) {
        String bizType = record.getBizType();
        String summary = summarize(error);

        // a) 不可恢复异常：直接死信，跳过退避
        if (isUnrecoverable(error)) {
            try {
                repository.markDead(record.getId(), record.getRetryCount(),
                        DeadReason.UNRECOVERABLE, summary);
            } catch (Throwable t) {
                LOG.error("Outpost 回写不可恢复死信失败，messageId={}, bizType={}",
                        record.getId(), bizType, t);
            }
            metrics.onDeliveryFail(bizType, durationMs);
            metrics.onDead(bizType, DeadReason.UNRECOVERABLE);
            deadNotifier.notifyDead(OutboxDeliveryMessage.from(record),
                    DeadReason.UNRECOVERABLE, error);
            return;
        }

        int newRetryCount = record.getRetryCount() + 1;

        // b) 辅助消息：失败即停，不计死信、不告警
        if (record.isAuxiliary()) {
            try {
                repository.markFailed(record.getId(), newRetryCount,
                        backoffPolicy.nextDelaySeconds(newRetryCount), summary);
            } catch (Throwable t) {
                LOG.error("Outpost 回写辅助消息失败状态异常，messageId={}, bizType={}",
                        record.getId(), bizType, t);
            }
            LOG.warn("Outpost 辅助消息投递失败，按设计停在 FAILED 不再重试，messageId={}, bizType={}, bizKey={}, error={}",
                    record.getId(), bizType, record.getBizKey(), summary);
            metrics.onDeliveryFail(bizType, durationMs);
            return;
        }

        // c) 普通消息：重试耗尽进死信，否则按退避置 FAILED
        if (newRetryCount >= record.getMaxRetry()) {
            try {
                repository.markDead(record.getId(), newRetryCount,
                        DeadReason.RETRY_EXHAUSTED, summary);
            } catch (Throwable t) {
                LOG.error("Outpost 回写重试耗尽死信失败，messageId={}, bizType={}",
                        record.getId(), bizType, t);
            }
            metrics.onDeliveryFail(bizType, durationMs);
            metrics.onDead(bizType, DeadReason.RETRY_EXHAUSTED);
            deadNotifier.notifyDead(OutboxDeliveryMessage.from(record),
                    DeadReason.RETRY_EXHAUSTED, error);
            return;
        }

        try {
            repository.markFailed(record.getId(), newRetryCount,
                    backoffPolicy.nextDelaySeconds(newRetryCount), summary);
        } catch (Throwable t) {
            LOG.error("Outpost 回写重试状态失败，messageId={}, bizType={}",
                    record.getId(), bizType, t);
        }
        metrics.onDeliveryFail(bizType, durationMs);
        metrics.onRetry(newRetryCount);
    }

    private boolean isUnrecoverable(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof UnrecoverableDeliveryException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private String summarize(Throwable error) {
        if (error == null) {
            return "UnknownError";
        }
        String message = error.getMessage();
        String summary = (message == null || message.isEmpty())
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
        if (summary.length() > MAX_ERROR_MSG_LENGTH) {
            return summary.substring(0, MAX_ERROR_MSG_LENGTH);
        }
        return summary;
    }
}
