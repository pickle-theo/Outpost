package com.outpost.starter.repository;

import com.outpost.core.message.OutboxMessageRecord;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * outpost_message 行映射：时间列 Timestamp 转 LocalDateTime，dead_reason 允许为空。
 */
public class OutboxMessageRowMapper implements RowMapper<OutboxMessageRecord> {

    @Override
    public OutboxMessageRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(rs.getLong("id"));
        record.setBizType(rs.getString("biz_type"));
        record.setBizKey(rs.getString("biz_key"));
        record.setPayload(rs.getString("payload"));
        record.setMessageType(rs.getInt("message_type"));
        record.setStatus(rs.getInt("status"));
        record.setRetryCount(rs.getInt("retry_count"));
        record.setMaxRetry(rs.getInt("max_retry"));
        record.setDeliverAttempt(rs.getInt("deliver_attempt"));
        record.setNextRetryTime(toLocalDateTime(rs.getTimestamp("next_retry_time")));
        record.setVersion(rs.getInt("version"));
        record.setErrorMsg(rs.getString("error_msg"));

        int deadReason = rs.getInt("dead_reason");
        record.setDeadReason(rs.wasNull() ? null : deadReason);

        record.setTraceId(rs.getString("trace_id"));
        record.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        record.setUpdateTime(toLocalDateTime(rs.getTimestamp("update_time")));
        return record;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
