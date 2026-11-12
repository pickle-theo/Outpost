package com.outpost.core.message;

import java.time.LocalDateTime;

/**
 * 本地消息表行对象，字段与 outpost_message 表列一一对应。
 */
public class OutboxMessageRecord {

    private Long id;
    private String bizType;
    private String bizKey;
    private String payload;
    private int messageType;
    private int status;
    private int retryCount;
    private int maxRetry;
    private int deliverAttempt;
    private LocalDateTime nextRetryTime;
    private int version;
    private String errorMsg;
    private Integer deadReason;
    private String traceId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public OutboxMessageRecord() {
    }

    /**
     * @return 是否为辅助消息（延迟双删的第二次删除）
     */
    public boolean isAuxiliary() {
        return messageType == MessageType.AUXILIARY.code();
    }

    /**
     * @return 当前状态枚举
     */
    public MessageStatus statusEnum() {
        return MessageStatus.fromCode(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizKey() {
        return bizKey;
    }

    public void setBizKey(String bizKey) {
        this.bizKey = bizKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public int getMessageType() {
        return messageType;
    }

    public void setMessageType(int messageType) {
        this.messageType = messageType;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public int getDeliverAttempt() {
        return deliverAttempt;
    }

    public void setDeliverAttempt(int deliverAttempt) {
        this.deliverAttempt = deliverAttempt;
    }

    public LocalDateTime getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(LocalDateTime nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Integer getDeadReason() {
        return deadReason;
    }

    public void setDeadReason(Integer deadReason) {
        this.deadReason = deadReason;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
