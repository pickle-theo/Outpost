package com.outpost.starter.repository;

import com.outpost.core.message.OutboxMessageRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxTableGuardTest {

    @Test
    void firstInsertOnMissingTableThrowsClearDdlHint() {
        JdbcDataSource dataSource = dataSource();
        OutboxSchemaInitializer initializer =
                new OutboxSchemaInitializer(dataSource, new TableNameResolver(), false);
        AtomicInteger checks = new AtomicInteger();
        OutboxTableGuard guard = new OutboxTableGuard("outpost_message", () -> {
            checks.incrementAndGet();
            return initializer.checkTableExists();
        });
        JdbcOutpostMessageRepository repository =
                new JdbcOutpostMessageRepository(dataSource, new TableNameResolver(), guard);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insertBatch(Collections.singletonList(record())));
        assertTrue(error.getMessage().contains("outpost_message"));
        assertTrue(error.getMessage().contains("DDL"));
        assertEquals(1, checks.get());
    }

    @Test
    void existingTableIsCheckedOnlyOnce() throws Exception {
        JdbcDataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
        }
        OutboxSchemaInitializer initializer =
                new OutboxSchemaInitializer(dataSource, new TableNameResolver(), false);
        AtomicInteger checks = new AtomicInteger();
        OutboxTableGuard guard = new OutboxTableGuard("outpost_message", () -> {
            checks.incrementAndGet();
            return initializer.checkTableExists();
        });
        JdbcOutpostMessageRepository repository =
                new JdbcOutpostMessageRepository(dataSource, new TableNameResolver(), guard);

        assertEquals(1, repository.insertBatch(Collections.singletonList(record())));
        assertEquals(1, repository.insertBatch(Collections.singletonList(record())));
        assertEquals(1, checks.get());
    }

    private static OutboxMessageRecord record() {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setBizType("cache_delete");
        record.setBizKey("order:1");
        record.setPayload("order:1");
        record.setMessageType(0);
        record.setMaxRetry(10);
        record.setNextRetryTime(LocalDateTime.now());
        return record;
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost_guard_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
