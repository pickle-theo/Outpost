package com.outpost.core.monitor;

import com.outpost.core.message.DeadReason;

/**
 * 指标埋点 SPI，默认实现为 {@link NoopOutpostMetrics}。
 */
public interface OutpostMetrics {

    void onMessageProduced(int count);

    void onDeliverySuccess(String bizType, long durationMs);

    void onDeliveryFail(String bizType, long durationMs);

    void onDead(String bizType, DeadReason reason);

    void onScheduleDuration(long durationMs);

    void onRetry(int retryCount);
}
