package com.outpost.starter.repository;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 首次写入前的消息表存在性守卫。
 *
 * <p>{@code auto-create-table=false} 时启动不检查、不报错；只有第一次真正写入消息时，
 * 才校验表是否存在并给出「执行 DDL」的明确提示，避免把表缺失伪装成普通 SQL 异常。
 * 一旦确认存在，后续写入不再重复查询。</p>
 */
public final class OutboxTableGuard {

    private final BooleanSupplier tableExistsChecker;
    private final String tableName;
    private final AtomicBoolean verified = new AtomicBoolean(false);

    public OutboxTableGuard(String tableName, BooleanSupplier tableExistsChecker) {
        if (tableExistsChecker == null) {
            throw new IllegalArgumentException("tableExistsChecker 不能为 null");
        }
        this.tableName = tableName;
        this.tableExistsChecker = tableExistsChecker;
    }

    public void assertAvailable() {
        if (verified.get()) {
            return;
        }
        boolean exists;
        try {
            exists = tableExistsChecker.getAsBoolean();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Outpost 无法确认消息表 [" + tableName + "] 是否存在，"
                    + "请检查数据源连接与权限", e);
        }
        if (!exists) {
            throw new IllegalStateException("Outpost 消息表 [" + tableName + "] 不存在，且 "
                    + "outpost.auto-create-table=false。请先由 DBA 执行交付脚本中的建表 DDL"
                    + "（scripts/ddl/outpost_message.sql），或在非生产环境开启 "
                    + "outpost.auto-create-table=true 由 SDK 幂等建表；本次消息注册已失败。");
        }
        verified.set(true);
    }
}
