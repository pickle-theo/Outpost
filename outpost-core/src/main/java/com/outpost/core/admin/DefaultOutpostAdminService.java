package com.outpost.core.admin;

import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.config.OutpostPropertiesValidator;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.retry.BackoffPolicy;
import com.outpost.core.retry.DefaultBackoffPolicy;
import com.outpost.core.retry.DynamicBackoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 运维管理接口默认实现。
 *
 * <p>查询与统计默认过滤辅助消息；{@code updateConfig} 只接受热更白名单，
 * 校验不通过时整体回滚到变更前的配置快照，不留半更新状态。</p>
 */
public final class DefaultOutpostAdminService implements OutpostAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOutpostAdminService.class);

    private static final long DEFAULT_HANDLER_UPPER_MILLIS = 1000L;
    private static final int MAX_BATCH_SIZE = 1000;

    private final OutpostAdminRepository repository;
    private final OutpostProperties properties;
    private final DynamicBackoffPolicy dynamicBackoffPolicy;
    private final OutpostPropertiesValidator validator;
    private final DeliveryHandlerRegistry registry;
    private final DataSource dataSource;

    public DefaultOutpostAdminService(OutpostAdminRepository repository, OutpostProperties properties) {
        this(repository, properties, null, null, null, null);
    }

    public DefaultOutpostAdminService(OutpostAdminRepository repository,
                                      OutpostProperties properties,
                                      DynamicBackoffPolicy dynamicBackoffPolicy,
                                      OutpostPropertiesValidator validator,
                                      DeliveryHandlerRegistry registry,
                                      DataSource dataSource) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        this.repository = repository;
        this.properties = properties;
        this.dynamicBackoffPolicy = dynamicBackoffPolicy;
        this.validator = validator;
        this.registry = registry;
        this.dataSource = dataSource;
    }

    @Override
    public OutpostPage<OutboxDeliveryMessage> queryMessages(MessageQuery query) {
        MessageQuery effective = query == null ? new MessageQuery() : query;
        effective.offset();
        long total = repository.countMessages(effective);
        List<OutboxMessageRecord> records = repository.selectMessages(effective);
        return page(records, total, effective);
    }

    @Override
    public OutpostPage<OutboxDeliveryMessage> queryDeadMessages(DeadMessageQuery query) {
        DeadMessageQuery effective = query == null ? new DeadMessageQuery() : query;
        effective.offset();
        long total = repository.countMessages(effective);
        List<OutboxMessageRecord> records = repository.selectMessages(effective);
        return page(records, total, effective);
    }

    @Override
    public void resendMessage(Long messageId) {
        batchResendMessages(Collections.singletonList(messageId));
    }

    @Override
    public void batchResendMessages(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("messageIds 不能为空");
        }
        List<Long> ids = new ArrayList<Long>(messageIds.size());
        for (Long id : messageIds) {
            if (id == null || id <= 0L) {
                throw new IllegalArgumentException("messageId 必须为正数，实际值: " + id);
            }
            ids.add(id);
        }
        int affected = repository.resendMessages(ids);
        if (affected < ids.size()) {
            LOG.warn("Outpost 重发部分消息未生效，请求数={}, 实际重置数={}，仅 PENDING/PROCESSING 之外的状态可重发",
                    ids.size(), affected);
        }
    }

    @Override
    public OutpostStats getStats() {
        return repository.stats();
    }

    @Override
    public void updateConfig(OutpostConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (config.isEmpty()) {
            return;
        }
        for (String key : config.getChanges().keySet()) {
            if (!OutpostConfig.supportedKeys().contains(key)) {
                throw new OutpostConfigurationException(
                        "Outpost 配置项 [" + key + "] 不支持热更，已拒绝本次变更；支持热更的配置项: "
                                + OutpostConfig.supportedKeys()
                                + "。其余配置必须修改配置文件并重启应用后生效");
            }
        }

        OutpostProperties snapshot = copyOf(properties);
        try {
            applyChanges(properties, config);
            validateAfterChange();
            if (dynamicBackoffPolicy != null && config.contains(OutpostConfig.BACKOFF_STEPS)) {
                dynamicBackoffPolicy.setDelegate(
                        new DefaultBackoffPolicy(properties.getRetry().getBackoffSteps()));
            }
        } catch (RuntimeException e) {
            restore(properties, snapshot);
            throw e;
        }
        LOG.info("Outpost 热更配置已生效: {}", config.getChanges().keySet());
    }

    private void validateAfterChange() {
        if (validator != null && dataSource != null) {
            validator.validateAndNormalize(properties, registry, dataSource);
            return;
        }
        // 无装配上下文时仍保证 §4.4 耦合公式与参数区间不被热更破坏
        OutpostProperties.Schedule schedule = properties.getSchedule();
        if (schedule.getBatchSize() < 1 || schedule.getBatchSize() > MAX_BATCH_SIZE) {
            throw new OutpostConfigurationException("schedule.batch-size 必须在 [1, 1000] 区间");
        }
        if (schedule.getQueueCapacity() < 10L * schedule.getBatchSize()) {
            throw new OutpostConfigurationException(
                    "schedule.batch-size 变更违反 queue-capacity >= 10 × batch-size");
        }
        double window = ((double) schedule.getBatchSize()) * DEFAULT_HANDLER_UPPER_MILLIS
                / Math.max(1, schedule.getCoreSize());
        double halfTimeout = schedule.getProcessingTimeoutSeconds() * 1000L * 0.5D;
        if (!(window < halfTimeout)) {
            throw new OutpostConfigurationException(
                    "schedule.batch-size 变更违反 §4.4 耦合公式：batch-size × 单条耗时 / core-size="
                            + window + "ms 必须小于 processing-timeout × 0.5=" + halfTimeout + "ms");
        }
    }

    private void applyChanges(OutpostProperties target, OutpostConfig config) {
        if (config.contains(OutpostConfig.BACKOFF_STEPS)) {
            long[] steps = toLongArray(config.get(OutpostConfig.BACKOFF_STEPS), OutpostConfig.BACKOFF_STEPS);
            new DefaultBackoffPolicy(steps);
            target.getRetry().setBackoffSteps(steps);
        }
        if (config.contains(OutpostConfig.ENABLED_NAMESPACES)) {
            target.getDeliveryCache().setEnabledNamespaces(
                    toStringList(config.get(OutpostConfig.ENABLED_NAMESPACES)));
        }
        if (config.contains(OutpostConfig.CLEANUP_ENABLED)) {
            target.getCleanup().setEnabled(
                    toBoolean(config.get(OutpostConfig.CLEANUP_ENABLED), OutpostConfig.CLEANUP_ENABLED));
        }
        if (config.contains(OutpostConfig.DOUBLE_DELETE_ENABLED)) {
            target.getDeliveryCache().getDoubleDelete().setEnabled(
                    toBoolean(config.get(OutpostConfig.DOUBLE_DELETE_ENABLED),
                            OutpostConfig.DOUBLE_DELETE_ENABLED));
        }
        if (config.contains(OutpostConfig.DOUBLE_DELETE_DELAY_MILLIS)) {
            long delay = toLong(config.get(OutpostConfig.DOUBLE_DELETE_DELAY_MILLIS),
                    OutpostConfig.DOUBLE_DELETE_DELAY_MILLIS);
            if (delay < 0L) {
                throw new OutpostConfigurationException(
                        OutpostConfig.DOUBLE_DELETE_DELAY_MILLIS + " 不能为负数: " + delay);
            }
            target.getDeliveryCache().getDoubleDelete().setDelayMillis(delay);
        }
        if (config.contains(OutpostConfig.BATCH_SIZE)) {
            int batchSize = (int) toLong(config.get(OutpostConfig.BATCH_SIZE), OutpostConfig.BATCH_SIZE);
            target.getSchedule().setBatchSize(batchSize);
        }
    }

    private static OutpostPage<OutboxDeliveryMessage> page(List<OutboxMessageRecord> records,
                                                           long total,
                                                           MessageQuery query) {
        List<OutboxDeliveryMessage> items = new ArrayList<OutboxDeliveryMessage>();
        if (records != null) {
            for (OutboxMessageRecord record : records) {
                items.add(OutboxDeliveryMessage.from(record));
            }
        }
        return new OutpostPage<OutboxDeliveryMessage>(items, total, query.getPageNo(), query.getPageSize());
    }

    private static long toLong(Object value, String key) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new OutpostConfigurationException("配置项 " + key + " 不是合法整数: " + value);
            }
        }
        throw new OutpostConfigurationException("配置项 " + key + " 需要整数，实际类型: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static boolean toBoolean(Object value, String key) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        throw new OutpostConfigurationException("配置项 " + key + " 需要布尔值，实际类型: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value == null) {
            return result;
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        if (value instanceof String) {
            for (String part : ((String) value).split(",")) {
                if (!part.trim().isEmpty()) {
                    result.add(part.trim());
                }
            }
            return result;
        }
        throw new OutpostConfigurationException("配置项 " + OutpostConfig.ENABLED_NAMESPACES
                + " 需要字符串列表，实际类型: " + value.getClass().getName());
    }

    private static long[] toLongArray(Object value, String key) {
        if (value instanceof long[]) {
            return ((long[]) value).clone();
        }
        List<?> items;
        if (value instanceof List) {
            items = (List<?>) value;
        } else if (value instanceof String) {
            items = java.util.Arrays.asList(((String) value).split(","));
        } else if (value instanceof Number[]) {
            items = java.util.Arrays.asList((Number[]) value);
        } else {
            throw new OutpostConfigurationException("配置项 " + key + " 需要 long 数组/整数列表，实际类型: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        long[] steps = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            steps[i] = toLong(items.get(i), key + "[" + i + "]");
        }
        return steps;
    }

    /**
     * 生成配置快照，用于热更失败时整体回滚。
     */
    private static OutpostProperties copyOf(OutpostProperties source) {
        OutpostProperties copy = new OutpostProperties();
        copy.setEnabled(source.isEnabled());
        copy.setTablePrefix(source.getTablePrefix());
        copy.setAutoCreateTable(source.isAutoCreateTable());
        copy.setMaxPayloadBytes(source.getMaxPayloadBytes());
        copy.setBatchRegisterThreshold(source.getBatchRegisterThreshold());

        OutpostProperties.Schedule s = source.getSchedule();
        OutpostProperties.Schedule cs = copy.getSchedule();
        cs.setEnabled(s.isEnabled());
        cs.setType(s.getType());
        cs.setLockType(s.getLockType());
        cs.setFixedDelayMs(s.getFixedDelayMs());
        cs.setBatchSize(s.getBatchSize());
        cs.setCoreSize(s.getCoreSize());
        cs.setMaxSize(s.getMaxSize());
        cs.setQueueCapacity(s.getQueueCapacity());
        cs.setProcessingTimeoutSeconds(s.getProcessingTimeoutSeconds());
        cs.setDegradeAfterFailures(s.getDegradeAfterFailures());
        cs.setRedisLockKey(s.getRedisLockKey());
        cs.setRedisLockTimeoutSeconds(s.getRedisLockTimeoutSeconds());

        OutpostProperties.Retry r = source.getRetry();
        copy.getRetry().setMaxRetryCount(r.getMaxRetryCount());
        copy.getRetry().setBackoffSteps(r.getBackoffSteps() == null ? null : r.getBackoffSteps().clone());

        OutpostProperties.DeliveryCache d = source.getDeliveryCache();
        OutpostProperties.DeliveryCache cd = copy.getDeliveryCache();
        cd.setEnabled(d.isEnabled());
        cd.setHandlerType(d.getHandlerType());
        cd.setRedisTimeoutMs(d.getRedisTimeoutMs());
        cd.setEnabledNamespaces(d.getEnabledNamespaces() == null
                ? null : new ArrayList<String>(d.getEnabledNamespaces()));
        cd.getDoubleDelete().setEnabled(d.getDoubleDelete().isEnabled());
        cd.getDoubleDelete().setDelayMillis(d.getDoubleDelete().getDelayMillis());
        OutpostProperties.Tag t = d.getTag();
        OutpostProperties.Tag ct = cd.getTag();
        ct.setDelaySeconds(t.getDelaySeconds());
        ct.setLockExpireMillis(t.getLockExpireMillis());
        ct.setLockSleepMillis(t.getLockSleepMillis());
        ct.setEmptyExpireSeconds(t.getEmptyExpireSeconds());
        ct.setRandomExpireAdjustment(t.getRandomExpireAdjustment());
        ct.setReadClient(t.getReadClient());

        OutpostProperties.Cleanup c = source.getCleanup();
        OutpostProperties.Cleanup cc = copy.getCleanup();
        cc.setEnabled(c.isEnabled());
        cc.setSuccessRetainDays(c.getSuccessRetainDays());
        cc.setDeadRetainDays(c.getDeadRetainDays());
        cc.setBatchSize(c.getBatchSize());
        cc.setFixedDelayMs(c.getFixedDelayMs());
        return copy;
    }

    private static void restore(OutpostProperties target, OutpostProperties snapshot) {
        target.setEnabled(snapshot.isEnabled());
        target.setTablePrefix(snapshot.getTablePrefix());
        target.setAutoCreateTable(snapshot.isAutoCreateTable());
        target.setMaxPayloadBytes(snapshot.getMaxPayloadBytes());
        target.setBatchRegisterThreshold(snapshot.getBatchRegisterThreshold());
        target.setSchedule(snapshot.getSchedule());
        target.setRetry(snapshot.getRetry());
        target.setDeliveryCache(snapshot.getDeliveryCache());
        target.setCleanup(snapshot.getCleanup());
    }
}
