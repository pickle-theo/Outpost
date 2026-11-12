package com.outpost.starter.repository;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.MessageType;
import com.outpost.core.message.OutboxMessageRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOutpostMessageRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcOutpostMessageRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcOutpostMessageRepository(dataSource, new TableNameResolver());
    }

    @Test
    void insertBatchShouldWritePendingRecords() {
        LocalDateTime nextRetryTime = LocalDateTime.now().plusHours(1).withNano(0);
        int inserted = repository.insertBatch(Arrays.asList(
                newRecord("order:1", nextRetryTime, 10),
                newRecord("order:2", nextRetryTime, 5)));
        assertEquals(2, inserted);

        List<OutboxMessageRecord> records = repository.selectByIds(ids());
        assertEquals(2, records.size());

        OutboxMessageRecord record = records.get(0);
        assertEquals("cache_delete", record.getBizType());
        assertEquals("order:1", record.getBizKey());
        assertEquals("payload-order:1", record.getPayload());
        assertEquals(MessageType.NORMAL.code(), record.getMessageType());
        assertEquals(MessageStatus.PENDING.code(), record.getStatus());
        assertEquals(0, record.getRetryCount());
        assertEquals(10, record.getMaxRetry());
        assertEquals(0, record.getDeliverAttempt());
        assertEquals(0, record.getVersion());
        assertEquals(nextRetryTime, record.getNextRetryTime());
        assertEquals("trace-order:1", record.getTraceId());
        assertNull(record.getDeadReason());
        assertNull(record.getErrorMsg());
        assertNotNull(record.getCreateTime());
        assertNotNull(record.getUpdateTime());
    }

    @Test
    void insertBatchShouldReturnZeroForEmptyList() {
        assertEquals(0, repository.insertBatch(Collections.emptyList()));
        assertEquals(0, repository.insertBatch(null));
        assertEquals(0L, repository.countAll());
    }

    @Test
    void selectDueShouldFilterByRetryCountAndNextRetryTime() {
        LocalDateTime now = LocalDateTime.now();
        repository.insertBatch(Arrays.asList(
                newRecord("b", now.minusMinutes(1).withNano(0), 10),
                newRecord("a", now.minusMinutes(10).withNano(0), 10),
                newRecord("future", now.plusHours(1).withNano(0), 10),
                newRecord("exhausted", now.minusMinutes(10).withNano(0), 10)));

        List<Long> ids = ids();
        jdbc.update("UPDATE outpost_message SET retry_count = max_retry WHERE id = ?", ids.get(3));

        List<OutboxMessageRecord> due = repository.selectDue(10);
        assertEquals(2, due.size());
        assertEquals("a", due.get(0).getBizKey());
        assertEquals("b", due.get(1).getBizKey());

        assertEquals(1, repository.selectDue(1).size());
    }

    @Test
    void claimBatchShouldOnlyClaimPendingAndFailed() {
        repository.insertBatch(Arrays.asList(
                newRecord("pending", LocalDateTime.now(), 10),
                newRecord("failed", LocalDateTime.now(), 10),
                newRecord("success", LocalDateTime.now(), 10)));
        List<Long> ids = ids();
        jdbc.update("UPDATE outpost_message SET status = 3 WHERE id = ?", ids.get(1));
        jdbc.update("UPDATE outpost_message SET status = 2 WHERE id = ?", ids.get(2));

        assertEquals(2, repository.claimBatch(ids));
        assertEquals(0, repository.claimBatch(Collections.<Long>emptyList()));

        Map<Long, OutboxMessageRecord> records = byId(repository.selectByIds(ids));
        assertEquals(MessageStatus.PROCESSING.code(), records.get(ids.get(0)).getStatus());
        assertEquals(1, records.get(ids.get(0)).getDeliverAttempt());
        assertEquals(1, records.get(ids.get(0)).getVersion());

        assertEquals(MessageStatus.PROCESSING.code(), records.get(ids.get(1)).getStatus());
        assertEquals(1, records.get(ids.get(1)).getDeliverAttempt());
        assertEquals(1, records.get(ids.get(1)).getVersion());

        assertEquals(MessageStatus.SUCCESS.code(), records.get(ids.get(2)).getStatus());
        assertEquals(0, records.get(ids.get(2)).getDeliverAttempt());
        assertEquals(0, records.get(ids.get(2)).getVersion());
    }

    @Test
    void claimOneShouldFailOnSecondAttempt() {
        repository.insertBatch(Collections.singletonList(newRecord("cas", LocalDateTime.now(), 10)));
        Long id = ids().get(0);

        assertTrue(repository.claimOne(id));
        assertFalse(repository.claimOne(id));

        OutboxMessageRecord record = repository.selectByIds(Collections.singletonList(id)).get(0);
        assertEquals(MessageStatus.PROCESSING.code(), record.getStatus());
        assertEquals(1, record.getDeliverAttempt());
        assertEquals(1, record.getVersion());
    }

    @Test
    void markSuccessBatchShouldOnlyUpdateProcessing() {
        repository.insertBatch(Arrays.asList(
                newRecord("s1", LocalDateTime.now(), 10),
                newRecord("s2", LocalDateTime.now(), 10),
                newRecord("pending", LocalDateTime.now(), 10)));
        List<Long> ids = ids();
        assertEquals(2, repository.claimBatch(ids.subList(0, 2)));

        assertEquals(2, repository.markSuccessBatch(ids));
        assertEquals(0, repository.markSuccessBatch(ids));

        Map<Long, OutboxMessageRecord> records = byId(repository.selectByIds(ids));
        OutboxMessageRecord success = records.get(ids.get(0));
        assertEquals(MessageStatus.SUCCESS.code(), success.getStatus());
        assertEquals(2, success.getVersion());
        assertEquals(1, success.getDeliverAttempt());

        assertEquals(MessageStatus.PENDING.code(), records.get(ids.get(2)).getStatus());
    }

    @Test
    void markFailedShouldUpdateRetryCountAndNextRetryTime() {
        repository.insertBatch(Collections.singletonList(newRecord("fail", LocalDateTime.now(), 10)));
        Long id = ids().get(0);
        assertTrue(repository.claimOne(id));

        assertEquals(1, repository.markFailed(id, 1, 60, "boom"));

        OutboxMessageRecord record = repository.selectByIds(Collections.singletonList(id)).get(0);
        assertEquals(MessageStatus.FAILED.code(), record.getStatus());
        assertEquals(1, record.getRetryCount());
        assertEquals("boom", record.getErrorMsg());
        assertEquals(2, record.getVersion());

        Timestamp now = jdbc.queryForObject("SELECT NOW()", Timestamp.class);
        assertNotNull(now);
        long delaySeconds = java.time.Duration.between(now.toLocalDateTime(),
                record.getNextRetryTime()).getSeconds();
        assertTrue(delaySeconds >= 55 && delaySeconds <= 70, "nextRetryTime delay=" + delaySeconds);

        assertEquals(0, repository.markFailed(id, 2, 60, "again"));
    }

    @Test
    void markDeadShouldSetDeadReason() {
        repository.insertBatch(Collections.singletonList(newRecord("dead", LocalDateTime.now(), 3)));
        Long id = ids().get(0);
        assertTrue(repository.claimOne(id));

        assertEquals(1, repository.markDead(id, 3, DeadReason.RETRY_EXHAUSTED, "fatal"));

        OutboxMessageRecord record = repository.selectByIds(Collections.singletonList(id)).get(0);
        assertEquals(MessageStatus.DEAD.code(), record.getStatus());
        assertEquals(3, record.getRetryCount());
        assertEquals(DeadReason.RETRY_EXHAUSTED.code(), record.getDeadReason().intValue());
        assertEquals("fatal", record.getErrorMsg());
        assertEquals(2, record.getVersion());

        assertEquals(1L, repository.countDead());
        assertEquals(1L, repository.countAll());
    }

    @Test
    void resetTimeoutProcessingShouldOnlyResetTimedOutRows() {
        repository.insertBatch(Arrays.asList(
                newRecord("timeout", LocalDateTime.now(), 10),
                newRecord("processing", LocalDateTime.now(), 10)));
        List<Long> ids = ids();
        assertEquals(2, repository.claimBatch(ids));

        jdbc.update("UPDATE outpost_message SET update_time = TIMESTAMPADD(SECOND, -3600, NOW()) WHERE id = ?",
                ids.get(0));

        assertEquals(1, repository.resetTimeoutProcessing(60));

        Map<Long, OutboxMessageRecord> records = byId(repository.selectByIds(ids));
        assertEquals(MessageStatus.PENDING.code(), records.get(ids.get(0)).getStatus());
        assertEquals(MessageStatus.PROCESSING.code(), records.get(ids.get(1)).getStatus());

        assertTrue(repository.claimOne(ids.get(0)));
        assertEquals(1L, repository.countRedelivery());
    }

    @Test
    void selectByIdsShouldReturnEmptyForEmptyInput() {
        assertTrue(repository.selectByIds(Collections.<Long>emptyList()).isEmpty());
        assertTrue(repository.selectByIds(null).isEmpty());
    }

    private OutboxMessageRecord newRecord(String bizKey, LocalDateTime nextRetryTime, int maxRetry) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setBizType("cache_delete");
        record.setBizKey(bizKey);
        record.setPayload("payload-" + bizKey);
        record.setMessageType(MessageType.NORMAL.code());
        record.setMaxRetry(maxRetry);
        record.setNextRetryTime(nextRetryTime);
        record.setTraceId("trace-" + bizKey);
        return record;
    }

    private List<Long> ids() {
        return new ArrayList<>(jdbc.queryForList("SELECT id FROM outpost_message ORDER BY id ASC", Long.class));
    }

    private Map<Long, OutboxMessageRecord> byId(List<OutboxMessageRecord> records) {
        return records.stream().collect(Collectors.toMap(OutboxMessageRecord::getId, Function.identity()));
    }
}
