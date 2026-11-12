package com.outpost.core.cleanup;

import com.outpost.core.admin.OutpostAdminRepository;
import com.outpost.core.config.OutpostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据清理任务：只删除保留期外的 {@code SUCCESS} 与 {@code DEAD} 消息，
 * {@code FAILED} / {@code PROCESSING} 永不清理。
 *
 * <p>启动时固定主键上界，按主键范围分批 DELETE，避免清理到任务运行期间新写入的消息，
 * 也避免一次性大事务锁表。</p>
 */
public final class MessageCleanupTask {

    private static final Logger LOG = LoggerFactory.getLogger(MessageCleanupTask.class);

    /** 单轮清理的绝对上限，防止异常数据导致长循环。 */
    private static final int MAX_DELETED_PER_ROUND = 100000;

    private final OutpostAdminRepository repository;
    private final OutpostProperties properties;

    public MessageCleanupTask(OutpostAdminRepository repository, OutpostProperties properties) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 执行一轮清理。
     *
     * @return 实际删除行数；开关关闭时返回 0 且不访问数据库
     */
    public int runOnce() {
        OutpostProperties.Cleanup cleanup = properties.getCleanup();
        if (cleanup == null || !cleanup.isEnabled()) {
            LOG.debug("Outpost 清理任务已关闭，跳过本轮");
            return 0;
        }
        int batchSize = Math.max(1, cleanup.getBatchSize());
        long maxId = repository.maxMessageId();
        if (maxId <= 0L) {
            return 0;
        }
        int total = 0;
        while (total < MAX_DELETED_PER_ROUND) {
            int deleted = repository.deleteExpired(maxId,
                    cleanup.getSuccessRetainDays(), cleanup.getDeadRetainDays(), batchSize);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
        }
        if (total > 0) {
            LOG.info("Outpost 清理任务完成，deleted={}, maxId={}, successRetainDays={}, deadRetainDays={}",
                    total, maxId, cleanup.getSuccessRetainDays(), cleanup.getDeadRetainDays());
        }
        return total;
    }
}
