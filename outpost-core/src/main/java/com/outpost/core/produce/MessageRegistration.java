package com.outpost.core.produce;

import com.outpost.core.message.MessageType;

/**
 * 事务内待落库的消息注册项，不可变。
 *
 * <p>由 {@link TransactionMessageCollector} 在当前事务的 {@code beforeCommit} 阶段
 * 转换为 {@link com.outpost.core.message.OutboxMessageRecord} 并批量写入。</p>
 */
public final class MessageRegistration {

    private final String bizType;
    private final String bizKey;
    private final String payload;
    private final long delayMills;
    private final int messageType;
    private final int maxRetry;

    private MessageRegistration(String bizType, String bizKey, String payload,
                                long delayMills, int messageType, int maxRetry) {
        if (bizType == null || bizType.trim().isEmpty()) {
            throw new IllegalArgumentException("bizType 不能为空");
        }
        if (bizKey == null || bizKey.trim().isEmpty()) {
            throw new IllegalArgumentException("bizKey 不能为空");
        }
        if (delayMills < 0) {
            throw new IllegalArgumentException("delayMills 不能为负数: " + delayMills);
        }
        if (maxRetry < 0) {
            throw new IllegalArgumentException("maxRetry 不能为负数: " + maxRetry);
        }
        this.bizType = bizType;
        this.bizKey = bizKey;
        this.payload = payload;
        this.delayMills = delayMills;
        this.messageType = messageType;
        this.maxRetry = maxRetry;
    }

    /**
     * 构造普通消息注册项。
     *
     * @param maxRetry 最大重试次数，通常取 {@code outpost.retry.max-retry-count}
     */
    public static MessageRegistration normal(String bizType, String bizKey, String payload,
                                             long delayMills, int maxRetry) {
        return new MessageRegistration(bizType, bizKey, payload, delayMills,
                MessageType.NORMAL.code(), maxRetry);
    }

    /**
     * 构造辅助消息注册项（延迟双删的第二次删除），最大重试次数强制为 1。
     */
    public static MessageRegistration auxiliary(String bizType, String bizKey, String payload,
                                                long delayMills) {
        return new MessageRegistration(bizType, bizKey, payload, delayMills,
                MessageType.AUXILIARY.code(), 1);
    }

    public String getBizType() {
        return bizType;
    }

    public String getBizKey() {
        return bizKey;
    }

    public String getPayload() {
        return payload;
    }

    public long getDelayMills() {
        return delayMills;
    }

    public int getMessageType() {
        return messageType;
    }

    public int getMaxRetry() {
        return maxRetry;
    }
}
