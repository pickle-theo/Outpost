package com.outpost.starter.repository;

import java.util.regex.Pattern;

/**
 * 消息表名解析：前缀只允许字母、数字与下划线，长度 0~32，用于安全拼接表名。
 */
public class TableNameResolver {

    public static final String DEFAULT_PREFIX = "outpost_";

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Za-z0-9_]{0,32}$");

    private final String prefix;

    public TableNameResolver() {
        this(DEFAULT_PREFIX);
    }

    public TableNameResolver(String prefix) {
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "表名前缀不合法: " + prefix + "，只允许字母、数字、下划线，长度 0~32（空串表示无前缀）");
        }
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * @return 消息表名：前缀 + message
     */
    public String messageTable() {
        return prefix + "message";
    }
}
