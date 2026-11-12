package com.outpost.core.monitor;

import com.outpost.core.message.DeadReason;

/**
 * 空实现：未接入监控系统时使用，避免内核到处判空。
 */
public final class NoopOutpostMetrics implements OutpostMetrics {

    public static final NoopOutpostMetrics INSTANCE = new NoopOutpostMetrics();

    private NoopOutpostMetrics() {
    }

    @Override
    public void onMessageProduced(int count) {
        // no-op
    }

    @Override
    public void onDeliverySuccess(String bizType, long durationMs) {
        // no-op
    }

    @Override
    public void onDeliveryFail(String bizType, long durationMs) {
        // no-op
    }

    @Override
    public void onDead(String bizType, DeadReason reason) {
        // no-op
    }

    @Override
    public void onScheduleDuration(long durationMs) {
        // no-op
    }

    @Override
    public void onRetry(int retryCount) {
        // no-op
    }
}
