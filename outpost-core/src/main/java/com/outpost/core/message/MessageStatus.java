package com.outpost.core.message;

/**
 * 消息状态。
 */
public enum MessageStatus {

    /** 待投递 */
    PENDING(0),
    /** 处理中（已被抢占） */
    PROCESSING(1),
    /** 投递成功 */
    SUCCESS(2),
    /** 投递失败，等待重试 */
    FAILED(3),
    /** 死信 */
    DEAD(4);

    private final int code;

    MessageStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageStatus fromCode(int code) {
        for (MessageStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的消息状态码: " + code);
    }
}
