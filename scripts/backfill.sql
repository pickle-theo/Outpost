-- =============================================================================
-- Outpost 紧急回灌脚本（示例，需由 DBA 按实际业务日志表调整后审核执行）
--
-- 占位符由 scripts/replay.sh 替换：
--   :SINCE / :UNTIL  业务变更时间窗口（含左不含右）
--   :NAMESPACE        业务域（用于拼 biz_key = namespace:cache_key）
--   :BIZ_TYPE         cache_delete 或 cache_tag_delete
--   :MAX_RETRY        回灌消息的最大重试次数
--   :TABLE            业务变更日志表名（示例 biz_cache_change_log）
--
-- biz_cache_change_log 仅为示例结构：id / namespace / cache_key / change_time / replayed。
-- 回灌前务必冻结相关业务写入，回灌后用 scripts/shadow-reconcile.sql 对账。
-- =============================================================================

INSERT INTO outpost_message (
    biz_type, biz_key, payload, message_type, status, retry_count, max_retry,
    deliver_attempt, next_retry_time, version, error_msg, dead_reason,
    trace_id, create_time, update_time
)
SELECT
    :BIZ_TYPE                                   AS biz_type,
    CONCAT(:NAMESPACE, ':', l.cache_key)        AS biz_key,
    l.cache_key                                 AS payload,
    0                                           AS message_type,
    0                                           AS status,
    0                                           AS retry_count,
    :MAX_RETRY                                  AS max_retry,
    0                                           AS deliver_attempt,
    NOW()                                       AS next_retry_time,
    0                                           AS version,
    NULL                                        AS error_msg,
    NULL                                        AS dead_reason,
    'backfill'                                  AS trace_id,
    NOW()                                       AS create_time,
    NOW()                                       AS update_time
FROM :TABLE l
WHERE l.change_time >= :SINCE
  AND l.change_time <  :UNTIL
  AND l.namespace = :NAMESPACE
  AND l.replayed = 0;

-- 标记业务日志已回灌（可选，取决于业务表是否允许更新）
-- UPDATE :TABLE SET replayed = 1
-- WHERE change_time >= :SINCE AND change_time < :UNTIL AND namespace = :NAMESPACE;
