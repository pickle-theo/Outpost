package com.outpost.core.delivery;

import com.outpost.core.message.OutboxMessageRecord;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 只读消息 DTO，作为 {@link DeliveryHandler} 的入参，避免暴露存储实体。
 *
 * <p>演进规则：只允许追加字段与 getter，不允许删除或修改既有字段语义。</p>
 */
public final class OutboxDeliveryMessage {

    private final Long id;
    private final String bizType;
    private final String bizKey;
    private final String payload;
    private final int deliverAttempt;
    private final String traceId;
    private final LocalDateTime createTime;

    public OutboxDeliveryMessage(Long id,
                                 String bizType,
                                 String bizKey,
                                 String payload,
                                 int deliverAttempt,
                                 String traceId,
                                 LocalDateTime createTime) {
        if (id == null) {
            throw new IllegalArgumentException("消息 id 不能为 null");
        }
        if (bizType == null || bizType.trim().isEmpty()) {
            throw new IllegalArgumentException("消息 bizType 不能为空");
        }
        if (bizKey == null || bizKey.trim().isEmpty()) {
            throw new IllegalArgumentException("消息 bizKey 不能为空");
        }
        this.id = id;
        this.bizType = bizType;
        this.bizKey = bizKey;
        this.payload = payload;
        this.deliverAttempt = deliverAttempt;
        this.traceId = traceId;
        this.createTime = createTime;
    }

    /**
     * 从存储行对象拷贝生成只读 DTO。
     */
    public static OutboxDeliveryMessage from(OutboxMessageRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record 不能为 null");
        }
        return new OutboxDeliveryMessage(
                record.getId(),
                record.getBizType(),
                record.getBizKey(),
                record.getPayload(),
                record.getDeliverAttempt(),
                record.getTraceId(),
                record.getCreateTime());
    }

    public Long getId() {
        return id;
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

    public int getDeliverAttempt() {
        return deliverAttempt;
    }

    public String getTraceId() {
        return traceId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutboxDeliveryMessage)) {
            return false;
        }
        OutboxDeliveryMessage that = (OutboxDeliveryMessage) o;
        return deliverAttempt == that.deliverAttempt
                && Objects.equals(id, that.id)
                && Objects.equals(bizType, that.bizType)
                && Objects.equals(bizKey, that.bizKey)
                && Objects.equals(payload, that.payload)
                && Objects.equals(traceId, that.traceId)
                && Objects.equals(createTime, that.createTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, bizType, bizKey, payload, deliverAttempt, traceId, createTime);
    }

    @Override
    public String toString() {
        return "OutboxDeliveryMessage{"
                + "id=" + id
                + ", bizType='" + bizType + '\''
                + ", bizKey='" + bizKey + '\''
                + ", payload='" + payload + '\''
                + ", deliverAttempt=" + deliverAttempt
                + ", traceId='" + traceId + '\''
                + ", createTime=" + createTime
                + '}';
    }
}
