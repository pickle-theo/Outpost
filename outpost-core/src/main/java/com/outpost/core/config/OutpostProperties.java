package com.outpost.core.config;

import com.outpost.core.cache.CacheBizTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Outpost 配置项，对应 {@code outpost.*} 前缀。
 *
 * <p>普通 POJO，不依赖 Spring，绑定由 starter 完成。</p>
 */
public class OutpostProperties {

    /** SDK 总开关 */
    private boolean enabled = true;
    /** 表名前缀，白名单校验后拼接 */
    private String tablePrefix = "outpost_";
    /** 是否自动建表，默认关闭，生产由 DBA 执行 DDL */
    private boolean autoCreateTable = false;
    /** 单条 payload 上限（字节），超限抛异常 */
    private int maxPayloadBytes = 65536;
    /** 单事务消息数告警阈值 */
    private int batchRegisterThreshold = 200;

    private Schedule schedule = new Schedule();
    private Retry retry = new Retry();
    private DeliveryCache deliveryCache = new DeliveryCache();
    private Cleanup cleanup = new Cleanup();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public boolean isAutoCreateTable() {
        return autoCreateTable;
    }

    public void setAutoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getBatchRegisterThreshold() {
        return batchRegisterThreshold;
    }

    public void setBatchRegisterThreshold(int batchRegisterThreshold) {
        this.batchRegisterThreshold = batchRegisterThreshold;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public DeliveryCache getDeliveryCache() {
        return deliveryCache;
    }

    public void setDeliveryCache(DeliveryCache deliveryCache) {
        this.deliveryCache = deliveryCache;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    /**
     * 调度配置。
     */
    public static class Schedule {

        private boolean enabled = true;
        private String type = "redis-lock";
        private long fixedDelayMs = 5000L;
        private int batchSize = 100;
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 1000;
        private int processingTimeoutSeconds = 300;
        private int degradeAfterFailures = 3;
        private String redisLockKey = "outpost:schedule:lock";
        private int redisLockTimeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getProcessingTimeoutSeconds() {
            return processingTimeoutSeconds;
        }

        public void setProcessingTimeoutSeconds(int processingTimeoutSeconds) {
            this.processingTimeoutSeconds = processingTimeoutSeconds;
        }

        public int getDegradeAfterFailures() {
            return degradeAfterFailures;
        }

        public void setDegradeAfterFailures(int degradeAfterFailures) {
            this.degradeAfterFailures = degradeAfterFailures;
        }

        public String getRedisLockKey() {
            return redisLockKey;
        }

        public void setRedisLockKey(String redisLockKey) {
            this.redisLockKey = redisLockKey;
        }

        public int getRedisLockTimeoutSeconds() {
            return redisLockTimeoutSeconds;
        }

        public void setRedisLockTimeoutSeconds(int redisLockTimeoutSeconds) {
            this.redisLockTimeoutSeconds = redisLockTimeoutSeconds;
        }
    }

    /**
     * 重试配置。
     */
    public static class Retry {

        private int maxRetryCount = 10;
        private long[] backoffSteps = new long[]{1L, 3L, 10L, 30L, 60L, 300L};

        public int getMaxRetryCount() {
            return maxRetryCount;
        }

        public void setMaxRetryCount(int maxRetryCount) {
            this.maxRetryCount = maxRetryCount;
        }

        public long[] getBackoffSteps() {
            return backoffSteps;
        }

        public void setBackoffSteps(long[] backoffSteps) {
            this.backoffSteps = backoffSteps;
        }
    }

    /**
     * 缓存一致性配置。
     */
    public static class DeliveryCache {

        private boolean enabled = true;
        private String handlerType = CacheBizTypes.CACHE_DELETE;
        private int redisTimeoutMs = 1000;
        private List<String> enabledNamespaces = new ArrayList<String>();
        private DoubleDelete doubleDelete = new DoubleDelete();
        private Tag tag = new Tag();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHandlerType() {
            return handlerType;
        }

        public void setHandlerType(String handlerType) {
            this.handlerType = handlerType;
        }

        public int getRedisTimeoutMs() {
            return redisTimeoutMs;
        }

        public void setRedisTimeoutMs(int redisTimeoutMs) {
            this.redisTimeoutMs = redisTimeoutMs;
        }

        public List<String> getEnabledNamespaces() {
            return enabledNamespaces;
        }

        public void setEnabledNamespaces(List<String> enabledNamespaces) {
            this.enabledNamespaces = enabledNamespaces;
        }

        public DoubleDelete getDoubleDelete() {
            return doubleDelete;
        }

        public void setDoubleDelete(DoubleDelete doubleDelete) {
            this.doubleDelete = doubleDelete;
        }

        public Tag getTag() {
            return tag;
        }

        public void setTag(Tag tag) {
            this.tag = tag;
        }
    }

    /**
     * 延迟双删配置。
     */
    public static class DoubleDelete {

        private boolean enabled = false;
        private long delayMillis = 1000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getDelayMillis() {
            return delayMillis;
        }

        public void setDelayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
        }
    }

    /**
     * rockscache tag 模式配置。
     */
    public static class Tag {

        private int delaySeconds = 10;
        private long lockExpireMillis = 3000L;
        private long lockSleepMillis = 100L;
        private long emptyExpireSeconds = 60L;
        private double randomExpireAdjustment = 0.1D;
        private String readClient;

        public int getDelaySeconds() {
            return delaySeconds;
        }

        public void setDelaySeconds(int delaySeconds) {
            this.delaySeconds = delaySeconds;
        }

        public long getLockExpireMillis() {
            return lockExpireMillis;
        }

        public void setLockExpireMillis(long lockExpireMillis) {
            this.lockExpireMillis = lockExpireMillis;
        }

        public long getLockSleepMillis() {
            return lockSleepMillis;
        }

        public void setLockSleepMillis(long lockSleepMillis) {
            this.lockSleepMillis = lockSleepMillis;
        }

        public long getEmptyExpireSeconds() {
            return emptyExpireSeconds;
        }

        public void setEmptyExpireSeconds(long emptyExpireSeconds) {
            this.emptyExpireSeconds = emptyExpireSeconds;
        }

        public double getRandomExpireAdjustment() {
            return randomExpireAdjustment;
        }

        public void setRandomExpireAdjustment(double randomExpireAdjustment) {
            this.randomExpireAdjustment = randomExpireAdjustment;
        }

        public String getReadClient() {
            return readClient;
        }

        public void setReadClient(String readClient) {
            this.readClient = readClient;
        }
    }

    /**
     * 数据清理配置。
     */
    public static class Cleanup {

        private boolean enabled = true;
        private int successRetainDays = 3;
        private int deadRetainDays = 30;
        private int batchSize = 1000;
        private long fixedDelayMs = 86400000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSuccessRetainDays() {
            return successRetainDays;
        }

        public void setSuccessRetainDays(int successRetainDays) {
            this.successRetainDays = successRetainDays;
        }

        public int getDeadRetainDays() {
            return deadRetainDays;
        }

        public void setDeadRetainDays(int deadRetainDays) {
            this.deadRetainDays = deadRetainDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
