package com.outpost.starter.repository;

import com.outpost.core.admin.MessageQuery;
import com.outpost.core.admin.OutpostStats;
import com.outpost.core.message.OutboxMessageRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOutpostMessageRepositoryAdminTest {

    private JdbcTemplate jdbc;
    private JdbcOutpostMessageRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost_admin_" + UUID.randomUUID().toString().replace("-", "")
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
    void countAndSelectFilterAuxiliaryMessagesAndPaginate() {
        insert(3, 0);
        insert(1, 1);

        MessageQuery query = new MessageQuery();
        assertEquals(3L, repository.countMessages(query));
        query.setPageSize(2);
        assertEquals(2, repository.selectMessages(query).size());
        MessageQuery secondPage = new MessageQuery();
        secondPage.setPageNo(2);
        secondPage.setPageSize(2);
        assertEquals(1, repository.selectMessages(secondPage).size());

        query.setIncludeAuxiliary(true);
        assertEquals(4L, repository.countMessages(query));

        MessageQuery statusQuery = new MessageQuery();
        statusQuery.setStatus(0);
        assertEquals(3L, repository.countMessages(statusQuery));
    }

    @Test
    void resendResetsOnlyTerminalStates() {
        insert(2, 0);
        List<Long> ids = jdbc.queryForList("SELECT id FROM outpost_message ORDER BY id ASC", Long.class);
        jdbc.update("UPDATE outpost_message SET status = 4, retry_count = 3, error_msg = 'boom',"
                + " dead_reason = 2, version = 1 WHERE id = ?", ids.get(0));

        assertEquals(1, repository.resendMessages(ids));

        OutboxMessageRecord resend = repository.selectByIds(Collections.singletonList(ids.get(0))).get(0);
        assertEquals(0, resend.getStatus());
        assertEquals(0, resend.getRetryCount());
        assertNull(resend.getErrorMsg());
        assertNull(resend.getDeadReason());
        assertEquals(2, resend.getVersion());
    }

    @Test
    void statsExcludeAuxiliaryAndExposeRedeliveryRatio() {
        insert(3, 0);
        insert(1, 1);
        List<Long> ids = jdbc.queryForList("SELECT id FROM outpost_message ORDER BY id ASC", Long.class);
        jdbc.update("UPDATE outpost_message SET status = 2 WHERE id = ?", ids.get(0));
        jdbc.update("UPDATE outpost_message SET status = 4, dead_reason = 1 WHERE id = ?", ids.get(1));
        jdbc.update("UPDATE outpost_message SET status = 3, deliver_attempt = 5 WHERE id = ?", ids.get(2));

        OutpostStats stats = repository.stats();
        assertEquals(3L, stats.getTotal());
        assertEquals(1L, stats.getSuccess());
        assertEquals(1L, stats.getDead());
        assertEquals(1L, stats.getFailed());
        assertEquals(1L, stats.getDeadRetryExhausted());
        assertTrue(stats.getRedeliveryRatio() > 0D);
        assertEquals(ids.get(ids.size() - 1).longValue(), repository.maxMessageId());
    }

    @Test
    void deleteExpiredKeepsFailedAndProcessingRows() {
        insert(4, 0);
        List<Long> ids = jdbc.queryForList("SELECT id FROM outpost_message ORDER BY id ASC", Long.class);
        jdbc.update("UPDATE outpost_message SET status = 2 WHERE id = ?", ids.get(0));
        jdbc.update("UPDATE outpost_message SET status = 4, dead_reason = 1 WHERE id = ?", ids.get(1));
        jdbc.update("UPDATE outpost_message SET status = 3 WHERE id = ?", ids.get(2));
        jdbc.update("UPDATE outpost_message SET status = 1 WHERE id = ?", ids.get(3));
        jdbc.update("UPDATE outpost_message SET update_time = TIMESTAMPADD(DAY, -40, NOW())");

        assertEquals(2, repository.deleteExpired(repository.maxMessageId(), 3, 30, 1000));

        List<Integer> statuses = jdbc.queryForList(
                "SELECT status FROM outpost_message ORDER BY id ASC", Integer.class);
        assertEquals(Arrays.asList(3, 1), statuses);
    }

    @Test
    void deleteExpiredRespectsRetentionBoundaryAndBatchSize() {
        insert(3, 0);
        List<Long> ids = jdbc.queryForList("SELECT id FROM outpost_message ORDER BY id ASC", Long.class);
        jdbc.update("UPDATE outpost_message SET status = 2 WHERE id IN (?, ?)",
                ids.get(0), ids.get(1));
        jdbc.update("UPDATE outpost_message SET status = 4 WHERE id = ?", ids.get(2));
        jdbc.update("UPDATE outpost_message SET update_time = TIMESTAMPADD(DAY, -2, NOW()) WHERE id = ?",
                ids.get(0));
        jdbc.update("UPDATE outpost_message SET update_time = TIMESTAMPADD(DAY, -10, NOW()) WHERE id = ?",
                ids.get(1));
        jdbc.update("UPDATE outpost_message SET update_time = TIMESTAMPADD(DAY, -20, NOW()) WHERE id = ?",
                ids.get(2));

        assertEquals(1, repository.deleteExpired(repository.maxMessageId(), 3, 30, 1));
        assertEquals(0, repository.deleteExpired(repository.maxMessageId(), 3, 30, 1000));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM outpost_message", Long.class)
                .longValue());
    }

    private void insert(int count, int messageType) {
        List<OutboxMessageRecord> records = new ArrayList<OutboxMessageRecord>();
        for (int i = 0; i < count; i++) {
            OutboxMessageRecord record = new OutboxMessageRecord();
            record.setBizType("cache_delete");
            record.setBizKey("order:" + messageType + ":" + i);
            record.setPayload("order:" + i);
            record.setMessageType(messageType);
            record.setMaxRetry(10);
            record.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
            record.setTraceId("trace-" + i);
            records.add(record);
        }
        repository.insertBatch(records);
    }
}
