package com.outpost.core.message;

/**
 * 消息类型。
 */
public enum MessageType {

    /** 普通消息 */
    NORMAL(0),
    /** 辅助消息（延迟双删的第二次删除） */
    AUXILIARY(1);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
