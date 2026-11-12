package com.outpost.core.cleanup;

import com.outpost.core.schedule.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 清理任务调度器：每日固定间隔触发，复用分布式锁避免多实例重复清理。
 *
 * <p>调度线程池为守护线程，生命周期由装配层的 {@code SmartLifecycle} 管理。</p>
 */
public final class OutpostCleanupScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutpostCleanupScheduler.class);

    private static final long LOCK_TTL_MILLIS = 60000L;

    private final MessageCleanupTask task;
    private final DistributedLock lock;
    private final String lockKey;
    private final long fixedDelayMs;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;

    public OutpostCleanupScheduler(MessageCleanupTask task,
                                   DistributedLock lock,
                                   String lockKey,
                                   long fixedDelayMs) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        if (fixedDelayMs < 100L) {
            throw new IllegalArgumentException("cleanup.fixed-delay-ms 必须 >= 100ms");
        }
        this.task = task;
        this.lock = lock;
        this.lockKey = lockKey == null || lockKey.trim().isEmpty()
                ? "outpost:cleanup:lock" : lockKey;
        this.fixedDelayMs = fixedDelayMs;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "outpost-cleanup");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor = service;
        service.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, fixedDelayMs, fixedDelayMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started.set(false);
        ScheduledExecutorService service = this.executor;
        this.executor = null;
        if (service != null) {
            service.shutdownNow();
        }
    }

    /**
     * 单轮清理；包可见以便单测直接驱动。
     */
    void tick() {
        if (lock == null) {
            runQuietly();
            return;
        }
        boolean acquired = false;
        try {
            acquired = lock.tryLock(lockKey, LOCK_TTL_MILLIS);
            if (acquired) {
                runQuietly();
            }
        } catch (Throwable t) {
            LOG.error("Outpost 清理任务执行异常，已隔离", t);
        } finally {
            if (acquired) {
                try {
                    lock.unlock(lockKey);
                } catch (Throwable t) {
                    LOG.warn("Outpost 释放清理锁异常，等待锁自动过期，lockKey={}", lockKey, t);
                }
            }
        }
    }

    private void runQuietly() {
        try {
            task.runOnce();
        } catch (Throwable t) {
            LOG.error("Outpost 清理任务执行异常，已隔离", t);
        }
    }
}
