package com.outpost.starter.repository;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxSchemaInitializerTest {

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost_schema_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
    }

    @Test
    void autoCreateDisabledShouldDoNothing() {
        OutboxSchemaInitializer initializer =
                new OutboxSchemaInitializer(dataSource, new TableNameResolver(), false);

        initializer.initialize();

        assertFalse(initializer.checkTableExists());
    }

    @Test
    void checkTableExistsShouldDetectExistingTable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
        }
        OutboxSchemaInitializer initializer =
                new OutboxSchemaInitializer(dataSource, new TableNameResolver(), false);

        assertTrue(initializer.checkTableExists());
    }
}
