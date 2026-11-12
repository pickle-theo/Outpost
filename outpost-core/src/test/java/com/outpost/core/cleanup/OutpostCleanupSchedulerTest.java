package com.outpost.core.cleanup;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.schedule.DistributedLock;
import com.outpost.core.support.InMemoryOutpostAdminRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutpostCleanupSchedulerTest {

    @Test
    void runsOnlyWhenLockAcquiredAndAlwaysReleases() {
        CountingRepository repository = new CountingRepository();
        OutpostProperties properties = new OutpostProperties();
        MessageCleanupTask task = new MessageCleanupTask(repository, properties);
        RecordingLock lock = new RecordingLock(true);
        OutpostCleanupScheduler scheduler =
                new OutpostCleanupScheduler(task, lock, "outpost:cleanup:lock", 1000L);

        scheduler.tick();

        assertEquals(1, repository.maxIdCalls.get());
        assertEquals(1, lock.unlocks.get());
    }

    @Test
    void skipsRoundWhenLockNotAcquired() {
        CountingRepository repository = new CountingRepository();
        OutpostProperties properties = new OutpostProperties();
        MessageCleanupTask task = new MessageCleanupTask(repository, properties);
        RecordingLock lock = new RecordingLock(false);
        OutpostCleanupScheduler scheduler =
                new OutpostCleanupScheduler(task, lock, "outpost:cleanup:lock", 1000L);

        scheduler.tick();

        assertEquals(0, repository.maxIdCalls.get());
        assertEquals(0, lock.unlocks.get());
    }

    @Test
    void disabledCleanupDoesNotQueryDatabase() {
        CountingRepository repository = new CountingRepository();
        OutpostProperties properties = new OutpostProperties();
        properties.getCleanup().setEnabled(false);
        MessageCleanupTask task = new MessageCleanupTask(repository, properties);
        RecordingLock lock = new RecordingLock(true);
        OutpostCleanupScheduler scheduler =
                new OutpostCleanupScheduler(task, lock, "outpost:cleanup:lock", 1000L);

        scheduler.tick();

        assertEquals(0, repository.maxIdCalls.get());
        assertEquals(1, lock.unlocks.get());
    }

    private static final class CountingRepository extends InMemoryOutpostAdminRepository {

        private final AtomicInteger maxIdCalls = new AtomicInteger();

        @Override
        public long maxMessageId() {
            maxIdCalls.incrementAndGet();
            return 0L;
        }
    }

    private static final class RecordingLock implements DistributedLock {

        private final boolean acquired;
        private final AtomicInteger unlocks = new AtomicInteger();

        private RecordingLock(boolean acquired) {
            this.acquired = acquired;
        }

        @Override
        public boolean tryLock(String key, long ttlMillis) {
            return acquired;
        }

        @Override
        public void unlock(String key) {
            unlocks.incrementAndGet();
        }
    }
}
