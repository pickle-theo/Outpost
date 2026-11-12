package com.outpost.starter.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 消息表建表器：autoCreate=true 时执行幂等 DDL；否则不执行任何操作。
 */
public class OutboxSchemaInitializer {

    private static final String SCHEMA_RESOURCE = "META-INF/outpost/schema-mysql.sql";
    private static final String TABLE_PLACEHOLDER = "__TABLE__";

    private final JdbcTemplate jdbcTemplate;
    private final TableNameResolver tableNameResolver;
    private final boolean autoCreate;

    public OutboxSchemaInitializer(DataSource dataSource, TableNameResolver tableNameResolver, boolean autoCreate) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tableNameResolver = tableNameResolver;
        this.autoCreate = autoCreate;
    }

    /**
     * autoCreate=true 时执行建表 DDL（CREATE TABLE IF NOT EXISTS，可重复执行）。
     */
    public void initialize() {
        if (!autoCreate) {
            return;
        }
        String schema = readSchema();
        String sql = schema.replace(TABLE_PLACEHOLDER, tableNameResolver.messageTable()).trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        jdbcTemplate.execute(sql);
    }

    /**
     * @return 消息表是否存在
     */
    public boolean checkTableExists() {
        String sql = "SELECT COUNT(*) FROM " + tableNameResolver.messageTable() + " WHERE 1 = 0";
        try {
            jdbcTemplate.queryForObject(sql, Long.class);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private String readSchema() {
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到建表脚本资源: " + SCHEMA_RESOURCE);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败: " + SCHEMA_RESOURCE, e);
        }
    }
}
