package com.outpost.starter.cache;

/**
 * Redis 操作超时异常，属于可重试语义：本次投递失败，等待退避后重试。
 */
public class RedisOperationTimeoutException extends RuntimeException {

    public RedisOperationTimeoutException(String message) {
        super(message);
    }

    public RedisOperationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
