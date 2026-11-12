package com.outpost.starter.monitor;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.OutpostMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer 指标实现，指标名称固定，不随业务配置变化。
 *
 * <p>{@code MeterRegistry} 不存在时装配层降级为 {@code NoopOutpostMetrics}，
 * 本类只在 micrometer-core 位于 classpath 且存在 MeterRegistry bean 时被加载。</p>
 */
public final class MicrometerOutpostMetrics implements OutpostMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerOutpostMetrics.class);

    public static final String PRODUCE_TOTAL = "outpost.message.produce.total";
    public static final String DELIVERY_SUCCESS_TOTAL = "outpost.message.delivery.success.total";
    public static final String DELIVERY_FAIL_TOTAL = "outpost.message.delivery.fail.total";
    public static final String DEAD_GAUGE = "outpost.message.dead.gauge";
    public static final String REDELIVERY_RATIO = "outpost.message.redelivery.ratio";
    public static final String SCHEDULE_DURATION_MS = "outpost.schedule.duration.ms";
    public static final String DELIVERY_DURATION_MS = "outpost.delivery.duration.ms";
    public static final String RETRY_COUNT_DISTRIBUTION = "outpost.retry.count.distribution";

    private static final long RATIO_CACHE_MILLIS = 1000L;

    private final MeterRegistry registry;
    private final OutpostMessageRepository repository;
    private final Counter produceCounter;
    private final Timer scheduleTimer;
    private final Timer deliveryTimer;
    private final DistributionSummary retryDistribution;
    private final AtomicLong deadCount = new AtomicLong();
    private final ConcurrentHashMap<String, Counter> successCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> failCounters = new ConcurrentHashMap<String, Counter>();

    private volatile long ratioCachedAt;
    private volatile double ratioCached;

    public MicrometerOutpostMetrics(MeterRegistry registry, OutpostMessageRepository repository) {
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为 null");
        }
        this.registry = registry;
        this.repository = repository;
        this.produceCounter = Counter.builder(PRODUCE_TOTAL)
                .description("事务内注册并写入消息表的消息数")
                .register(registry);
        this.scheduleTimer = Timer.builder(SCHEDULE_DURATION_MS)
                .description("单轮调度耗时")
                .register(registry);
        this.deliveryTimer = Timer.builder(DELIVERY_DURATION_MS)
                .description("单条消息投递耗时")
                .register(registry);
        this.retryDistribution = DistributionSummary.builder(RETRY_COUNT_DISTRIBUTION)
                .description("失败重试次数分布")
                .register(registry);

        long initialDead = 0L;
        if (repository != null) {
            try {
                initialDead = repository.countDead();
            } catch (RuntimeException e) {
                LOG.debug("Outpost 初始化死信 gauge 时读取消息表失败，按 0 处理", e);
            }
        }
        deadCount.set(initialDead);
        Gauge.builder(DEAD_GAUGE, deadCount, AtomicLong::doubleValue)
                .description("当前死信消息数")
                .register(registry);
        Gauge.builder(REDELIVERY_RATIO, this, MicrometerOutpostMetrics::redeliveryRatio)
                .description("deliver_attempt > retry_count + 1 的消息占比")
                .register(registry);
    }

    @Override
    public void onMessageProduced(int count) {
        if (count > 0) {
            produceCounter.increment(count);
        }
    }

    @Override
    public void onDeliverySuccess(String bizType, long durationMs) {
        counterFor(successCounters, DELIVERY_SUCCESS_TOTAL, bizType).increment();
        deliveryTimer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onDeliveryFail(String bizType, long durationMs) {
        counterFor(failCounters, DELIVERY_FAIL_TOTAL, bizType).increment();
        deliveryTimer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onDead(String bizType, DeadReason reason) {
        deadCount.incrementAndGet();
    }

    @Override
    public void onScheduleDuration(long durationMs) {
        scheduleTimer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onRetry(int retryCount) {
        retryDistribution.record(Math.max(0, retryCount));
    }

    private Counter counterFor(ConcurrentHashMap<String, Counter> cache, String name, String bizType) {
        String tag = bizType == null || bizType.trim().isEmpty() ? "unknown" : bizType;
        Counter counter = cache.get(tag);
        if (counter == null) {
            counter = Counter.builder(name).tag("biz_type", tag).register(registry);
            Counter existing = cache.putIfAbsent(tag, counter);
            if (existing != null) {
                return existing;
            }
        }
        return counter;
    }

    private double redeliveryRatio() {
        long now = System.currentTimeMillis();
        if (now - ratioCachedAt < RATIO_CACHE_MILLIS) {
            return ratioCached;
        }
        double ratio = 0D;
        if (repository != null) {
            try {
                long total = repository.countAll();
                ratio = total <= 0L ? 0D : (double) repository.countRedelivery() / total;
            } catch (RuntimeException e) {
                LOG.debug("Outpost 计算重复投递占比失败，沿用上次取值", e);
                ratio = ratioCached;
            }
        }
        ratioCached = ratio;
        ratioCachedAt = now;
        return ratio;
    }
}
