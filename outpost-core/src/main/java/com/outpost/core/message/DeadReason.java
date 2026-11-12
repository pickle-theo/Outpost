package com.outpost.core.message;

/**
 * 死信原因。
 */
public enum DeadReason {

    /** 重试次数耗尽 */
    RETRY_EXHAUSTED(1),
    /** 不可恢复异常 */
    UNRECOVERABLE(2);

    private final int code;

    DeadReason(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DeadReason fromCode(int code) {
        for (DeadReason reason : values()) {
            if (reason.code == code) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知的死信原因码: " + code);
    }
}
