package com.outpost.core.config;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 启动期配置校验：不满足约束直接抛 {@link OutpostConfigurationException}，拒绝启动。
 *
 * <p>校验顺序固定，便于测试与问题定位。</p>
 */
public class OutpostPropertiesValidator {

    private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("^[A-Za-z0-9_]{0,32}$");
    private static final long DEFAULT_MAX_EXECUTION_MILLIS = 1000L;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final long MIN_FIXED_DELAY_MS = 100L;

    /**
     * @return warning 列表，无 warning 时返回空列表（不为 null）
     * @throws OutpostConfigurationException 首个校验失败项
     */
    public List<String> validateAndNormalize(OutpostProperties properties,
                                             DeliveryHandlerRegistry registry,
                                             DataSource dataSource) {
        if (properties == null) {
            throw fail("outpost 配置", null, "非 null");
        }
        List<String> warnings = new ArrayList<String>();

        // a) 数据源必须可解析
        if (dataSource == null) {
            throw fail("outpost.data-source", null, "必须能解析到唯一的 DataSource（非 null）");
        }

        // b) 表名前缀白名单
        String tablePrefix = properties.getTablePrefix();
        if (tablePrefix == null || !TABLE_PREFIX_PATTERN.matcher(tablePrefix).matches()) {
            throw fail("outpost.table-prefix", tablePrefix, "匹配 ^[A-Za-z0-9_]{0,32}$");
        }

        OutpostProperties.Schedule schedule = properties.getSchedule();
        if (schedule == null) {
            throw fail("outpost.schedule", null, "非 null");
        }

        // c) 调度基础参数
        if (schedule.getFixedDelayMs() < MIN_FIXED_DELAY_MS) {
            throw fail("outpost.schedule.fixed-delay-ms", schedule.getFixedDelayMs(),
                    ">= " + MIN_FIXED_DELAY_MS + "ms");
        }
        if (schedule.getBatchSize() < MIN_BATCH_SIZE || schedule.getBatchSize() > MAX_BATCH_SIZE) {
            throw fail("outpost.schedule.batch-size", schedule.getBatchSize(),
                    "取值区间 [" + MIN_BATCH_SIZE + ", " + MAX_BATCH_SIZE + "]");
        }

        OutpostProperties.Retry retry = properties.getRetry();
        if (retry == null) {
            throw fail("outpost.retry", null, "非 null");
        }
        if (retry.getMaxRetryCount() < 1) {
            throw fail("outpost.retry.max-retry-count", retry.getMaxRetryCount(), ">= 1");
        }
        if (schedule.getCoreSize() < 1) {
            throw fail("outpost.schedule.core-size", schedule.getCoreSize(), ">= 1");
        }
        if (schedule.getMaxSize() < schedule.getCoreSize()) {
            throw fail("outpost.schedule.max-size", schedule.getMaxSize(),
                    ">= core-size(" + schedule.getCoreSize() + ")");
        }
        long minQueueCapacity = 10L * schedule.getBatchSize();
        if (schedule.getQueueCapacity() < minQueueCapacity) {
            throw fail("outpost.schedule.queue-capacity", schedule.getQueueCapacity(),
                    ">= 10 × batch-size(" + minQueueCapacity + ")");
        }
        if (schedule.getProcessingTimeoutSeconds() < 1) {
            throw fail("outpost.schedule.processing-timeout-seconds",
                    schedule.getProcessingTimeoutSeconds(), ">= 1 秒");
        }

        // d) 退避阶梯：非空、全部 > 0、严格单调递增
        long[] backoffSteps = retry.getBackoffSteps();
        if (backoffSteps == null || backoffSteps.length == 0) {
            throw fail("outpost.retry.backoff-steps", backoffSteps == null ? null : Arrays.toString(backoffSteps),
                    "非空数组");
        }
        for (int i = 0; i < backoffSteps.length; i++) {
            if (backoffSteps[i] <= 0L) {
                throw fail("outpost.retry.backoff-steps[" + i + "]", backoffSteps[i], "> 0");
            }
            if (i > 0 && backoffSteps[i] <= backoffSteps[i - 1]) {
                throw fail("outpost.retry.backoff-steps", Arrays.toString(backoffSteps),
                        "严格单调递增（index " + (i - 1) + "=" + backoffSteps[i - 1]
                                + " 必须小于 index " + i + "=" + backoffSteps[i] + "）");
            }
        }

        // e) processingTimeout 必须覆盖 handler 最大执行耗时的 10 倍
        long handlerUpperMillis = maxHandlerExecutionMillis(registry);
        long processingTimeoutMillis = schedule.getProcessingTimeoutSeconds() * 1000L;
        long requiredTimeoutMillis = 10L * handlerUpperMillis;
        if (processingTimeoutMillis < requiredTimeoutMillis) {
            throw fail("outpost.schedule.processing-timeout-seconds",
                    schedule.getProcessingTimeoutSeconds(),
                    "processing-timeout-seconds × 1000 >= 10 × handler 最大执行耗时上界("
                            + handlerUpperMillis + "ms)，即 >= " + requiredTimeoutMillis + "ms");
        }

        // f) §4.4 耦合公式：batchSize × 单条耗时 / coreSize < processingTimeout × 0.5
        double batchWindowMillis = ((double) schedule.getBatchSize()) * handlerUpperMillis
                / schedule.getCoreSize();
        double halfTimeoutMillis = processingTimeoutMillis * 0.5D;
        if (!(batchWindowMillis < halfTimeoutMillis)) {
            throw fail("outpost.schedule.batch-size × handler 最大执行耗时 / core-size",
                    batchWindowMillis + "ms",
                    "< processing-timeout-seconds × 1000 × 0.5 = " + halfTimeoutMillis + "ms"
                            + "（§4.4 耦合公式，否则批量抢占会自行触发超时重置并制造重复投递）");
        }

        // g) 缓存处理器类型
        OutpostProperties.DeliveryCache deliveryCache = properties.getDeliveryCache();
        if (deliveryCache == null) {
            throw fail("outpost.delivery-cache", null, "非 null");
        }
        String handlerType = deliveryCache.getHandlerType();
        boolean tagMode = CacheBizTypes.CACHE_TAG_DELETE.equals(handlerType);
        if (deliveryCache.isEnabled()
                && !CacheBizTypes.CACHE_DELETE.equals(handlerType)
                && !tagMode) {
            throw fail("outpost.delivery-cache.handler-type", handlerType,
                    "取值必须是 " + CacheBizTypes.CACHE_DELETE + " 或 " + CacheBizTypes.CACHE_TAG_DELETE);
        }

        // h) tag 模式必须显式声明读客户端
        if (deliveryCache.isEnabled() && tagMode) {
            OutpostProperties.Tag tag = deliveryCache.getTag();
            if (tag == null) {
                throw fail("outpost.delivery-cache.tag", null, "非 null");
            }
            String readClient = tag.getReadClient();
            if (!"builtin".equals(readClient) && !"external".equals(readClient)) {
                throw fail("outpost.delivery-cache.tag.read-client", readClient,
                        "必须显式配置为 \"builtin\" 或 \"external\"；读路径必须是 rockscache 兼容 hash 语义"
                                + "（读取 hash 字段），普通 GET 会 WRONGTYPE");
            }
        }

        // i) tag 模式强制关闭延迟双删
        if (deliveryCache.isEnabled() && tagMode && deliveryCache.getDoubleDelete() != null
                && deliveryCache.getDoubleDelete().isEnabled()) {
            deliveryCache.getDoubleDelete().setEnabled(false);
            warnings.add("rockscache_tag 模式下延迟双删已自动关闭：锁+owner 已解决写回竞态，双删无收益");
        }

        // j) 调度类型必须非空（实现是否存在由装配阶段校验）
        String scheduleType = schedule.getType();
        if (scheduleType == null || scheduleType.trim().isEmpty()) {
            throw fail("outpost.schedule.type", scheduleType,
                    "非空字符串（具体实现是否存在由装配阶段校验）");
        }

        return warnings;
    }

    private static long maxHandlerExecutionMillis(DeliveryHandlerRegistry registry) {
        long max = -1L;
        if (registry != null) {
            for (DeliveryHandler handler : registry.all()) {
                if (handler != null && handler.getMaxExecutionMillis() > max) {
                    max = handler.getMaxExecutionMillis();
                }
            }
        }
        return max > 0L ? max : DEFAULT_MAX_EXECUTION_MILLIS;
    }

    private static OutpostConfigurationException fail(String item, Object actual, String expected) {
        return new OutpostConfigurationException(
                "Outpost 启动配置校验失败：" + item + " 实际值=" + actual + "，期望=" + expected);
    }
}
