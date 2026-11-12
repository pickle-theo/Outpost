package com.outpost.core.support;

import com.outpost.core.message.DeadReason;
import com.outpost.core.monitor.OutpostMetrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录型指标桩。
 */
public class RecordingOutpostMetrics implements OutpostMetrics {

    public final AtomicInteger produced = new AtomicInteger();
    public final AtomicInteger deliverySuccess = new AtomicInteger();
    public final AtomicInteger deliveryFail = new AtomicInteger();
    public final AtomicInteger dead = new AtomicInteger();
    public final AtomicInteger retry = new AtomicInteger();
    public final AtomicInteger scheduleDurationCount = new AtomicInteger();
    public final AtomicLong lastScheduleDurationMs = new AtomicLong(-1L);
    public final List<DeadReason> deadReasons = new CopyOnWriteArrayList<DeadReason>();
    public final List<Integer> retryCounts = new CopyOnWriteArrayList<Integer>();

    @Override
    public void onMessageProduced(int count) {
        produced.addAndGet(count);
    }

    @Override
    public void onDeliverySuccess(String bizType, long durationMs) {
        deliverySuccess.incrementAndGet();
    }

    @Override
    public void onDeliveryFail(String bizType, long durationMs) {
        deliveryFail.incrementAndGet();
    }

    @Override
    public void onDead(String bizType, DeadReason reason) {
        dead.incrementAndGet();
        deadReasons.add(reason);
    }

    @Override
    public void onScheduleDuration(long durationMs) {
        scheduleDurationCount.incrementAndGet();
        lastScheduleDurationMs.set(durationMs);
    }

    @Override
    public void onRetry(int retryCount) {
        retry.incrementAndGet();
        retryCounts.add(retryCount);
    }
}
