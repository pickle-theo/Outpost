-- =============================================================================
-- Outpost 影子对账脚本（示例，需按实际业务日志表调整）
--
-- 占位符由 scripts/shadow-reconcile.sh 替换：:SINCE / :UNTIL / :NAMESPACE / :TABLE
-- 输出四类结果：漏注册、重复注册、字段不一致、状态/死信分布。
-- =============================================================================

-- 1) 业务发生了变更但消息表没有对应消息（漏注册 / 消息丢失）
SELECT 'MISSING_MESSAGE' AS issue, l.id AS biz_log_id, l.cache_key, l.change_time
FROM :TABLE l
LEFT JOIN outpost_message m
       ON m.biz_key = CONCAT(l.namespace, ':', l.cache_key)
      AND m.create_time >= :SINCE
WHERE l.change_time >= :SINCE
  AND l.change_time <  :UNTIL
  AND l.namespace = :NAMESPACE
  AND m.id IS NULL;

-- 2) 同一业务键在时间窗内出现多条普通消息（重复注册）
SELECT 'DUPLICATE_MESSAGE' AS issue, m.biz_key, COUNT(*) AS message_count
FROM outpost_message m
WHERE m.create_time >= :SINCE
  AND m.create_time <  :UNTIL
  AND m.biz_key LIKE CONCAT(:NAMESPACE, ':%')
  AND m.message_type = 0
GROUP BY m.biz_key
HAVING COUNT(*) > 1;

-- 3) payload 与 biz_key 后缀不一致（字段构造错误）
SELECT 'PAYLOAD_MISMATCH' AS issue, m.id, m.biz_key, m.payload
FROM outpost_message m
WHERE m.create_time >= :SINCE
  AND m.create_time <  :UNTIL
  AND m.biz_key LIKE CONCAT(:NAMESPACE, ':%')
  AND m.payload <> SUBSTRING(m.biz_key, CHAR_LENGTH(:NAMESPACE) + 2);

-- 4) 状态与死信分布（用于阶段 1/2 退出判断）
SELECT 'STATUS_DISTRIBUTION' AS issue, m.status, m.dead_reason,
       COUNT(*) AS message_count,
       SUM(CASE WHEN m.deliver_attempt > m.retry_count + 1 THEN 1 ELSE 0 END) AS redelivery_count
FROM outpost_message m
WHERE m.create_time >= :SINCE
  AND m.create_time <  :UNTIL
GROUP BY m.status, m.dead_reason
ORDER BY m.status;
