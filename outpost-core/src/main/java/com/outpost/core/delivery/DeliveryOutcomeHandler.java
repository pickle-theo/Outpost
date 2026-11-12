package com.outpost.core.delivery;

import com.outpost.core.message.OutboxMessageRecord;

/**
 * 投递结果处理器：负责状态回写、指标与死信通知。
 */
public interface DeliveryOutcomeHandler {

    void onSuccess(OutboxMessageRecord record, long durationMs);

    void onFailure(OutboxMessageRecord record, Throwable error, long durationMs);
}
