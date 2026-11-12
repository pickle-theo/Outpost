package com.outpost.core.schedule;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryDispatcher;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.DeliveryOutcomeHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.support.InMemoryOutpostMessageRepository;
import com.outpost.core.support.RecordingOutpostMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LockingOutpostSchedulerTest {

    private static final String LOCK_KEY = "outpost:schedule:lock";

    private FakeDistributedLock lock;
    private InMemoryOutpostMessageRepository repository;
    private RecordingOutpostMetrics metrics;
    private OutpostProperties properties;
    private LockingOutpostScheduler scheduler;

    @BeforeEach
    void setUp() {
        lock = new FakeDistributedLock();
        repository = new InMemoryOutpostMessageRepository();
        metrics = new RecordingOutpostMetrics();
        properties = new OutpostProperties();
        properties.getSchedule().setFixedDelayMs(100L);
        properties.getSchedule().setDegradeAfterFailures(3);

        DeliveryDispatcher dispatcher = new DeliveryDispatcher(Runnable::run,
                new DeliveryHandlerRegistry(Collections.<DeliveryHandler>singletonList(new NoopHandler())),
                new NoopOutcomeHandler());
        ScheduleRoundExecutor roundExecutor =
                new ScheduleRoundExecutor(repository, dispatcher, properties, metrics);
        scheduler = new LockingOutpostScheduler(lock, roundExecutor, properties, LOCK_KEY);
    }

    @Test
    void shouldDegradeAfterConsecutiveLockFailuresAndRecover() {
        repository.due = Arrays.asList(
                record(1L, MessageStatus.PENDING), record(2L, MessageStatus.PENDING));
        lock.locked = false;

        scheduler.tick();
        scheduler.tick();
        assertEquals(2, lock.tryLockCalls.get());
        assertTrue(repository.claimBatchCalls.isEmpty());
        assertTrue(repository.claimOneCalls.isEmpty());

        scheduler.tick();
        assertEquals(3, lock.tryLockCalls.get());
        assertEquals(Arrays.asList(1L, 2L), repository.claimOneCalls);
        assertEquals(1, metrics.scheduleDurationCount.get());

        // 持续失败时保持降级，继续走逐条 CAS
        scheduler.tick();
        assertEquals(4, repository.claimOneCalls.size());
        assertEquals(2, metrics.scheduleDurationCount.get());

        // 加锁恢复后回到批量抢占模式
        lock.locked = true;
        scheduler.tick();
        assertEquals(1, repository.claimBatchCalls.size());
        assertEquals(Arrays.asList(1L, 2L), repository.claimBatchCalls.get(0));
        assertEquals(Collections.singletonList(LOCK_KEY), lock.unlockCalls);
        assertEquals(3, metrics.scheduleDurationCount.get());

        // 恢复后单次失败不会立即降级
        lock.locked = false;
        scheduler.tick();
        assertEquals(4, repository.claimOneCalls.size());
        assertEquals(3, metrics.scheduleDurationCount.get());
    }

    @Test
    void shouldReportRedisLockType() {
        assertEquals("redis-lock", scheduler.getType());
    }

    @Test
    void stopShouldStopSchedulingAndBeIdempotent() throws InterruptedException {
        lock.locked = true;
        repository.due = Collections.singletonList(record(1L, MessageStatus.PENDING));

        scheduler.start();
        waitUntilAtLeastOneExecution();

        scheduler.stop();
        int afterStop = repository.claimBatchCalls.size();
        Thread.sleep(300L);
        assertEquals(afterStop, repository.claimBatchCalls.size());

        scheduler.stop();
    }

    @Test
    void stopWithoutStartShouldNotThrow() {
        scheduler.stop();
        scheduler.stop();
        assertTrue(lock.tryLockCalls.get() == 0);
    }

    private void waitUntilAtLeastOneExecution() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (!repository.claimBatchCalls.isEmpty()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("调度器在超时时间内未执行任何一轮");
    }

    private static OutboxMessageRecord record(long id, MessageStatus status) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType("cache_delete");
        record.setBizKey("key-" + id);
        record.setPayload("payload");
        record.setStatus(status.code());
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setDeliverAttempt(1);
        return record;
    }

    private static final class FakeDistributedLock implements DistributedLock {

        private final AtomicInteger tryLockCalls = new AtomicInteger();
        private final List<String> unlockCalls = new CopyOnWriteArrayList<String>();
        private volatile boolean locked;

        @Override
        public boolean tryLock(String key, long ttlMillis) {
            tryLockCalls.incrementAndGet();
            return locked;
        }

        @Override
        public void unlock(String key) {
            unlockCalls.add(key);
        }
    }

    private static final class NoopHandler implements DeliveryHandler {

        @Override
        public String getBizType() {
            return "cache_delete";
        }

        @Override
        public void deliver(OutboxDeliveryMessage message) {
            // no-op
        }
    }

    private static final class NoopOutcomeHandler implements DeliveryOutcomeHandler {

        @Override
        public void onSuccess(OutboxMessageRecord record, long durationMs) {
            // no-op
        }

        @Override
        public void onFailure(OutboxMessageRecord record, Throwable error, long durationMs) {
            // no-op
        }
    }
}
