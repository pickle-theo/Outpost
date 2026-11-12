package com.outpost.core.schedule;

/**
 * 分布式锁 SPI。
 */
public interface DistributedLock {

    boolean tryLock(String key, long ttlMillis);

    void unlock(String key);
}
