package com.outpost.core.admin;

import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;

import java.util.List;

/**
 * 运维与清理所需的扩展持久层能力，独立于投递链路的 {@link OutpostMessageRepository}。
 */
public interface OutpostAdminRepository extends OutpostMessageRepository {

    long countMessages(MessageQuery query);

    List<OutboxMessageRecord> selectMessages(MessageQuery query);

    /**
     * 重发：status=PENDING、retry_count=0、next_retry_time=NOW()、清空 error_msg/dead_reason、version+1。
     *
     * @return 实际重置行数
     */
    int resendMessages(List<Long> ids);

    /**
     * 统计快照，口径排除辅助消息。
     */
    OutpostStats stats();

    /**
     * 当前最大主键，用于清理任务固定主键上界，避免清理到任务启动后新写入的消息。
     */
    long maxMessageId();

    /**
     * 按主键范围分批删除保留期外的 SUCCESS / DEAD 消息；FAILED / PROCESSING 永不删除。
     *
     * @param maxId               只包含 {@code id <= maxId} 的行
     * @param successRetainDays   SUCCESS 保留天数
     * @param deadRetainDays      DEAD 保留天数
     * @param batchSize           单批删除上限
     * @return 本批实际删除行数
     */
    int deleteExpired(long maxId, int successRetainDays, int deadRetainDays, int batchSize);
}
