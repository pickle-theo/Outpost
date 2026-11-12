package com.outpost.starter.autoconfigure;

import com.outpost.core.cleanup.OutpostCleanupScheduler;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.schedule.OutpostScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outpost 生命周期：统一启动/停止调度器与清理任务。
 *
 * <p>实现 Boot 2.3 起可用的 {@link SmartLifecycle} 全部方法（含
 * {@code stop(Runnable)}），保证 2.3.x ~ 2.7.x 行为一致。</p>
 */
public class OutpostLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OutpostLifecycle.class);

    private final List<OutpostScheduler> schedulers;
    private final OutpostProperties properties;
    private final OutpostCleanupScheduler cleanupScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<OutpostScheduler> startedSchedulers = new ArrayList<OutpostScheduler>();

    public OutpostLifecycle(List<OutpostScheduler> schedulers,
                            OutpostProperties properties,
                            OutpostCleanupScheduler cleanupScheduler) {
        this.schedulers = schedulers == null
                ? java.util.Collections.<OutpostScheduler>emptyList() : schedulers;
        this.properties = properties;
        this.cleanupScheduler = cleanupScheduler;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (properties.getSchedule().isEnabled()) {
            String type = properties.getSchedule().getType();
            for (OutpostScheduler scheduler : schedulers) {
                if (scheduler != null && type.equals(scheduler.getType())) {
                    scheduler.start();
                    startedSchedulers.add(scheduler);
                }
            }
        } else {
            LOG.info("Outpost 调度器已通过 outpost.schedule.enabled=false 关闭");
        }
        if (cleanupScheduler != null) {
            cleanupScheduler.start();
        }
        LOG.info("Outpost 已启动，schedule.enabled={}, cleanup.enabled={}",
                properties.getSchedule().isEnabled(), properties.getCleanup().isEnabled());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (OutpostScheduler scheduler : startedSchedulers) {
            try {
                scheduler.stop();
            } catch (RuntimeException e) {
                LOG.warn("Outpost 停止调度器异常", e);
            }
        }
        startedSchedulers.clear();
        if (cleanupScheduler != null) {
            cleanupScheduler.stop();
        }
        LOG.info("Outpost 已停止");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
