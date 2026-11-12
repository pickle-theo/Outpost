package com.outpost.starter.repository;

import com.outpost.core.admin.MessageQuery;
import com.outpost.core.admin.OutpostAdminRepository;
import com.outpost.core.admin.OutpostStats;
import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutboxMessageRecord;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 JdbcTemplate 的本地消息表仓储实现（同时提供运维与清理能力）。
 * <p>直接复用注入 DataSource 上的事务绑定连接，不开启独立事务。</p>
 */
public class JdbcOutpostMessageRepository implements OutpostAdminRepository {

    private static final int MAX_ERROR_MSG_LENGTH = 1024;

    private static final String COLUMNS = "id, biz_type, biz_key, payload, message_type, status, retry_count, "
            + "max_retry, deliver_attempt, next_retry_time, version, error_msg, dead_reason, trace_id, "
            + "create_time, update_time";

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final OutboxTableGuard tableGuard;
    private final OutboxMessageRowMapper rowMapper = new OutboxMessageRowMapper();

    public JdbcOutpostMessageRepository(DataSource dataSource, TableNameResolver tableNameResolver) {
        this(dataSource, tableNameResolver, null);
    }

    public JdbcOutpostMessageRepository(DataSource dataSource,
                                        TableNameResolver tableNameResolver,
                                        OutboxTableGuard tableGuard) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.table = tableNameResolver.messageTable();
        this.tableGuard = tableGuard;
    }

    @Override
    public int insertBatch(List<OutboxMessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        if (tableGuard != null) {
            tableGuard.assertAvailable();
        }
        String sql = "INSERT INTO " + table
                + " (biz_type, biz_key, payload, message_type, status, retry_count, max_retry, deliver_attempt,"
                + " next_retry_time, version, error_msg, dead_reason, trace_id, create_time, update_time)"
                + " VALUES (?, ?, ?, ?, 0, 0, ?, 0, ?, 0, ?, ?, ?, NOW(), NOW())";

        int[] affected = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                OutboxMessageRecord record = records.get(i);
                ps.setString(1, record.getBizType());
                ps.setString(2, record.getBizKey());
                ps.setString(3, record.getPayload());
                ps.setInt(4, record.getMessageType());
                ps.setInt(5, record.getMaxRetry());
                ps.setTimestamp(6, Timestamp.valueOf(record.getNextRetryTime()));
                ps.setString(7, truncate(record.getErrorMsg()));
                if (record.getDeadReason() == null) {
                    ps.setNull(8, Types.TINYINT);
                } else {
                    ps.setInt(8, record.getDeadReason());
                }
                ps.setString(9, record.getTraceId());
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });

        int total = 0;
        for (int count : affected) {
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    @Override
    public List<OutboxMessageRecord> selectDue(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + table
                + " WHERE status IN (0, 3) AND retry_count < max_retry AND next_retry_time <= NOW()"
                + " ORDER BY next_retry_time ASC, id ASC LIMIT ?";
        return jdbcTemplate.query(sql, rowMapper, limit);
    }

    @Override
    public int claimBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + table
                + " SET status = 1, deliver_attempt = deliver_attempt + 1, version = version + 1, update_time = NOW()"
                + " WHERE id IN (" + placeholders(ids.size()) + ") AND status IN (0, 3)";
        return jdbcTemplate.update(sql, ids.toArray());
    }

    @Override
    public boolean claimOne(Long id) {
        String sql = "UPDATE " + table
                + " SET status = 1, deliver_attempt = deliver_attempt + 1, version = version + 1, update_time = NOW()"
                + " WHERE id = ? AND status IN (0, 3)";
        return jdbcTemplate.update(sql, id) > 0;
    }

    @Override
    public List<OutboxMessageRecord> selectByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String sql = "SELECT " + COLUMNS + " FROM " + table
                + " WHERE id IN (" + placeholders(ids.size()) + ") ORDER BY id ASC";
        return jdbcTemplate.query(sql, rowMapper, ids.toArray());
    }

    @Override
    public int markSuccessBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + table + " SET status = 2, version = version + 1"
                + " WHERE id IN (" + placeholders(ids.size()) + ") AND status = 1";
        return jdbcTemplate.update(sql, ids.toArray());
    }

    @Override
    public int markFailed(Long id, int newRetryCount, long backoffSeconds, String errorMsg) {
        String sql = "UPDATE " + table
                + " SET status = 3, retry_count = ?, next_retry_time = TIMESTAMPADD(SECOND, ?, NOW()),"
                + " error_msg = ?, version = version + 1"
                + " WHERE id = ? AND status = 1";
        return jdbcTemplate.update(sql, newRetryCount, backoffSeconds, truncate(errorMsg), id);
    }

    @Override
    public int markDead(Long id, int newRetryCount, DeadReason reason, String errorMsg) {
        String sql = "UPDATE " + table
                + " SET status = 4, retry_count = ?, dead_reason = ?, error_msg = ?, version = version + 1"
                + " WHERE id = ? AND status = 1";
        return jdbcTemplate.update(sql, newRetryCount, reason.code(), truncate(errorMsg), id);
    }

    @Override
    public int resetTimeoutProcessing(int timeoutSeconds) {
        String sql = "UPDATE " + table
                + " SET status = 0"
                + " WHERE status = 1 AND update_time < TIMESTAMPADD(SECOND, ?, NOW())";
        return jdbcTemplate.update(sql, -timeoutSeconds);
    }

    @Override
    public long countDead() {
        return count("SELECT COUNT(*) FROM " + table + " WHERE status = 4");
    }

    @Override
    public long countAll() {
        return count("SELECT COUNT(*) FROM " + table);
    }

    @Override
    public long countRedelivery() {
        return count("SELECT COUNT(*) FROM " + table + " WHERE deliver_attempt > retry_count + 1");
    }

    @Override
    public long countMessages(MessageQuery query) {
        MessageQuery effective = query == null ? new MessageQuery() : query;
        effective.offset();
        List<Object> args = new ArrayList<Object>();
        String sql = "SELECT COUNT(*) FROM " + table + whereClause(effective, args);
        return count(sql, args);
    }

    @Override
    public List<OutboxMessageRecord> selectMessages(MessageQuery query) {
        MessageQuery effective = query == null ? new MessageQuery() : query;
        long offset = effective.offset();
        List<Object> args = new ArrayList<Object>();
        String sql = "SELECT " + COLUMNS + " FROM " + table + whereClause(effective, args)
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        args.add(effective.getPageSize());
        args.add(offset);
        return jdbcTemplate.query(sql, rowMapper, args.toArray());
    }

    @Override
    public int resendMessages(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + table + " SET status = 0, retry_count = 0, next_retry_time = NOW(),"
                + " error_msg = NULL, dead_reason = NULL, version = version + 1, update_time = NOW()"
                + " WHERE id IN (" + placeholders(ids.size()) + ") AND status IN (2, 3, 4)";
        return jdbcTemplate.update(sql, ids.toArray());
    }

    @Override
    public OutpostStats stats() {
        String sql = "SELECT COUNT(*) AS total,"
                + " SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS pending,"
                + " SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS processing,"
                + " SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS success,"
                + " SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS failed,"
                + " SUM(CASE WHEN status = 4 THEN 1 ELSE 0 END) AS dead,"
                + " SUM(CASE WHEN status = 4 AND dead_reason = 1 THEN 1 ELSE 0 END) AS dead_retry,"
                + " SUM(CASE WHEN status = 4 AND dead_reason = 2 THEN 1 ELSE 0 END) AS dead_unrecoverable,"
                + " SUM(CASE WHEN deliver_attempt > retry_count + 1 THEN 1 ELSE 0 END) AS redelivery"
                + " FROM " + table + " WHERE message_type = 0";
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            OutpostStats stats = new OutpostStats();
            stats.setTotal(rs.getLong("total"));
            stats.setPending(rs.getLong("pending"));
            stats.setProcessing(rs.getLong("processing"));
            stats.setSuccess(rs.getLong("success"));
            stats.setFailed(rs.getLong("failed"));
            stats.setDead(rs.getLong("dead"));
            stats.setDeadRetryExhausted(rs.getLong("dead_retry"));
            stats.setDeadUnrecoverable(rs.getLong("dead_unrecoverable"));
            stats.setRedelivery(rs.getLong("redelivery"));
            return stats;
        });
    }

    @Override
    public long maxMessageId() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public int deleteExpired(long maxId, int successRetainDays, int deadRetainDays, int batchSize) {
        int limit = Math.max(1, batchSize);
        String select = "SELECT id FROM " + table + " WHERE id <= ? AND ("
                + " (status = 2 AND update_time < TIMESTAMPADD(DAY, ?, NOW()))"
                + " OR (status = 4 AND update_time < TIMESTAMPADD(DAY, ?, NOW()))"
                + " ) ORDER BY id ASC LIMIT ?";
        List<Long> ids = jdbcTemplate.queryForList(select, Long.class,
                maxId, -successRetainDays, -deadRetainDays, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update("DELETE FROM " + table
                + " WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    private String whereClause(MessageQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (!query.isIncludeAuxiliary()) {
            sql.append(" AND message_type = 0");
        }
        if (hasText(query.getBizType())) {
            sql.append(" AND biz_type = ?");
            args.add(query.getBizType());
        }
        if (hasText(query.getBizKey())) {
            sql.append(" AND biz_key = ?");
            args.add(query.getBizKey());
        }
        if (query.getStatus() != null) {
            sql.append(" AND status = ?");
            args.add(query.getStatus());
        }
        if (query.getDeadReason() != null) {
            sql.append(" AND dead_reason = ?");
            args.add(query.getDeadReason());
        }
        if (query.getCreateTimeFrom() != null) {
            sql.append(" AND create_time >= ?");
            args.add(Timestamp.valueOf(query.getCreateTimeFrom()));
        }
        if (query.getCreateTimeTo() != null) {
            sql.append(" AND create_time <= ?");
            args.add(Timestamp.valueOf(query.getCreateTimeTo()));
        }
        return sql.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String placeholders(int size) {
        StringBuilder builder = new StringBuilder(size * 2);
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private String truncate(String errorMsg) {
        if (errorMsg == null || errorMsg.length() <= MAX_ERROR_MSG_LENGTH) {
            return errorMsg;
        }
        return errorMsg.substring(0, MAX_ERROR_MSG_LENGTH);
    }
}
