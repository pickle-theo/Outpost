package com.outpost.core.delivery;

/**
 * 不可恢复投递异常：内核直接置死信，跳过所有退避。
 */
public class UnrecoverableDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnrecoverableDeliveryException(String message) {
        super(message);
    }

    public UnrecoverableDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
