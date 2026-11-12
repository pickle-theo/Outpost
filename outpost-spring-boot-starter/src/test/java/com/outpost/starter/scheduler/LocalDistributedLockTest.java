package com.outpost.starter.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDistributedLockTest {

    @Test
    void sameKeyIsExclusiveUntilUnlocked() {
        LocalDistributedLock lock = new LocalDistributedLock();
        assertTrue(lock.tryLock("schedule", 1000L));
        assertFalse(lock.tryLock("schedule", 1000L), "同一 key 未释放时不得重入");
        assertTrue(lock.tryLock("cleanup", 1000L), "不同 key 互不影响");

        lock.unlock("schedule");
        assertTrue(lock.tryLock("schedule", 1000L));
        lock.unlock("schedule");
        lock.unlock("cleanup");
    }

    @Test
    void unlockReleasesAcrossThreads() throws Exception {
        LocalDistributedLock lock = new LocalDistributedLock();
        assertTrue(lock.tryLock("schedule", 1000L));

        AtomicBoolean acquired = new AtomicBoolean(true);
        Thread other = new Thread(() -> acquired.set(lock.tryLock("schedule", 1000L)));
        other.start();
        other.join();
        assertFalse(acquired.get(), "其它线程在锁未释放时不能获取");

        lock.unlock("schedule");
        AtomicBoolean acquiredAfterUnlock = new AtomicBoolean(false);
        Thread after = new Thread(() -> {
            acquiredAfterUnlock.set(lock.tryLock("schedule", 1000L));
            lock.unlock("schedule");
        });
        after.start();
        after.join();
        assertTrue(acquiredAfterUnlock.get());
    }

    @Test
    void rejectsBlankKey() {
        LocalDistributedLock lock = new LocalDistributedLock();
        assertThrows(IllegalArgumentException.class, () -> lock.tryLock(" ", 1000L));
    }
}
