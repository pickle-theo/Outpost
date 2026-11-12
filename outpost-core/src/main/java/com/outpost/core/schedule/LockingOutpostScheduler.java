package com.outpost.core.schedule;

import com.outpost.core.config.OutpostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于分布式锁的调度器：固定间隔触发，连续加锁失败自动降级为无锁逐条 CAS 模式。
 */
public final class LockingOutpostScheduler implements OutpostScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(LockingOutpostScheduler.class);

    private static final long LOCK_TTL_MILLIS = 30000L;

    private static final long AWAIT_TERMINATION_SECONDS = 5L;

    private final DistributedLock lock;
    private final ScheduleRoundExecutor roundExecutor;
    private final OutpostProperties properties;
    private final String lockKey;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;
    private volatile int consecutiveFailures;
    private volatile boolean degraded;

    public LockingOutpostScheduler(DistributedLock lock,
                                   ScheduleRoundExecutor roundExecutor,
                                   OutpostProperties properties,
                                   String lockKey) {
        if (lock == null) {
            throw new IllegalArgumentException("lock 不能为 null");
        }
        if (roundExecutor == null) {
            throw new IllegalArgumentException("roundExecutor 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("lockKey 不能为空");
        }
        this.lock = lock;
        this.roundExecutor = roundExecutor;
        this.properties = properties;
        this.lockKey = lockKey;
    }

    @Override
    public String getType() {
        return "redis-lock";
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "outpost-scheduler");
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
        }, 0L, properties.getSchedule().getFixedDelayMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        started.set(false);
        ScheduledExecutorService service = this.executor;
        this.executor = null;
        if (service == null) {
            return;
        }
        service.shutdownNow();
        try {
            if (!service.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Outpost 调度线程池未在 {} 秒内终止", AWAIT_TERMINATION_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 单轮调度逻辑，包可见以便单测直接驱动。
     */
    void tick() {
        try {
            if (lock.tryLock(lockKey, LOCK_TTL_MILLIS)) {
                boolean recovering = degraded;
                consecutiveFailures = 0;
                degraded = false;
                if (recovering) {
                    LOG.info("Outpost 调度重新获取分布式锁，退出降级模式");
                }
                try {
                    roundExecutor.runOnce();
                } finally {
                    safeUnlock();
                }
                return;
            }

            consecutiveFailures++;
            if (consecutiveFailures >= properties.getSchedule().getDegradeAfterFailures()) {
                if (!degraded) {
                    degraded = true;
                    LOG.warn("Outpost 连续 {} 轮获取调度锁失败，降级为无锁模式（逐条 CAS）",
                            consecutiveFailures);
                }
            }
            if (degraded) {
                roundExecutor.runOnceDegraded();
            }
        } catch (Throwable t) {
            LOG.error("Outpost 调度轮次执行异常，已隔离以保持调度继续", t);
        }
    }

    private void safeUnlock() {
        try {
            lock.unlock(lockKey);
        } catch (Throwable t) {
            LOG.error("Outpost 释放调度锁异常，将等待锁自动过期，lockKey={}", lockKey, t);
        }
    }
}
