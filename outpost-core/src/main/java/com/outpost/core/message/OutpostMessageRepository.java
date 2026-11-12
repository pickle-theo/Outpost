package com.outpost.core.message;

import java.util.List;

/**
 * 本地消息表持久层接口（投递链路所需方法）。
 */
public interface OutpostMessageRepository {

    /**
     * 批量写入消息，落库状态为 PENDING。
     *
     * @return 实际写入行数
     */
    int insertBatch(List<OutboxMessageRecord> records);

    /**
     * 查询到期待投递消息：status IN (0,3) AND retry_count &lt; max_retry AND next_retry_time &lt;= NOW()，
     * 按 next_retry_time、id 升序。
     */
    List<OutboxMessageRecord> selectDue(int limit);

    /**
     * 批量抢占：仅对 status IN (0,3) 的行生效，抢占后 status=PROCESSING、deliver_attempt+1、version+1。
     *
     * @return 实际抢占行数
     */
    int claimBatch(List<Long> ids);

    /**
     * 单条 CAS 抢占（降级模式），语义同 {@link #claimBatch(List)}。
     *
     * @return 是否抢占成功
     */
    boolean claimOne(Long id);

    /**
     * 按主键批量查询。
     */
    List<OutboxMessageRecord> selectByIds(List<Long> ids);

    /**
     * 批量标记成功：仅对 status=PROCESSING 的行生效，成功后 status=SUCCESS、version+1。
     *
     * @return 实际更新行数
     */
    int markSuccessBatch(List<Long> ids);

    /**
     * 标记失败并计算下次重试时间：仅对 status=PROCESSING 的行生效。
     *
     * @param newRetryCount   新的重试次数
     * @param backoffSeconds  距当前时间的退避秒数
     * @param errorMsg        异常摘要
     * @return 实际更新行数
     */
    int markFailed(Long id, int newRetryCount, long backoffSeconds, String errorMsg);

    /**
     * 标记死信：仅对 status=PROCESSING 的行生效。
     *
     * @return 实际更新行数
     */
    int markDead(Long id, int newRetryCount, DeadReason reason, String errorMsg);

    /**
     * 重置超时的处理中消息：status=PROCESSING 且 update_time 早于 now - timeoutSeconds 的行置为 PENDING。
     *
     * @return 实际重置行数
     */
    int resetTimeoutProcessing(int timeoutSeconds);

    /**
     * @return 死信总数
     */
    long countDead();

    /**
     * @return 消息总数
     */
    long countAll();

    /**
     * @return 重复投递行数（deliver_attempt &gt; retry_count + 1）
     */
    long countRedelivery();
}
