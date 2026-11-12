package com.outpost.core.retry;

/**
 * 退避策略 SPI。
 */
public interface BackoffPolicy {

    /**
     * @param retryCount 重试次数，从 1 开始
     * @return 距当前时间的退避秒数
     */
    long nextDelaySeconds(int retryCount);
}
