package com.outpost.starter.cache;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * rockscache 兼容的 Redis Hash 协议常量与 Lua 脚本。
 *
 * <p>缓存条目为一个 Hash，字段固定为：</p>
 * <ul>
 *   <li>{@code value}：缓存值</li>
 *   <li>{@code lockUntil}：秒级时间戳，{@code 0} 表示已被标记失效</li>
 *   <li>{@code lockOwner}：当前回源锁的持有者（uuid）</li>
 * </ul>
 *
 * <p>所有脚本都只操作单个 key，天然兼容 Redis Cluster（不跨 slot）。</p>
 */
public final class RockscacheScripts {

    public static final String FIELD_VALUE = "value";
    public static final String FIELD_LOCK_UNTIL = "lockUntil";
    public static final String FIELD_LOCK_OWNER = "lockOwner";

    /**
     * 标记删除：{@code lockUntil=0}、移除锁持有者，并按延迟时间续期，返回 1。
     */
    public static final DefaultRedisScript<Long> TAG_DELETE = new DefaultRedisScript<Long>(
            "redis.call('HSET', KEYS[1], '" + FIELD_LOCK_UNTIL + "', 0)\n"
                    + "redis.call('HDEL', KEYS[1], '" + FIELD_LOCK_OWNER + "')\n"
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1])\n"
                    + "return 1",
            Long.class);

    /**
     * owner 校验写回：仅当锁仍由本次回源持有时写入。
     *
     * <p>KEYS[1]=key；ARGV[1]=owner；ARGV[2]=value；ARGV[3]=lockUntil 秒级时间戳；
     * ARGV[4]=expireSeconds。返回 1 表示写入成功，0 表示已被他人接管（含 TagAsDeleted
     * 清掉 lockOwner 的场景），旧值不会覆盖新值。</p>
     */
    public static final DefaultRedisScript<Long> SET_VALUE = new DefaultRedisScript<Long>(
            "local owner = redis.call('HGET', KEYS[1], '" + FIELD_LOCK_OWNER + "')\n"
                    + "if owner ~= ARGV[1] then\n"
                    + "  return 0\n"
                    + "end\n"
                    + "redis.call('HSET', KEYS[1], '" + FIELD_VALUE + "', ARGV[2])\n"
                    + "redis.call('HSET', KEYS[1], '" + FIELD_LOCK_UNTIL + "', ARGV[3])\n"
                    + "redis.call('HDEL', KEYS[1], '" + FIELD_LOCK_OWNER + "')\n"
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4])\n"
                    + "return 1",
            Long.class);

    /**
     * 抢锁：{@code HSETNX lockOwner owner} 成功后写入 {@code lockUntil}，返回 1 表示抢到。
     *
     * <p>KEYS[1]=key；ARGV[1]=owner；ARGV[2]=lockUntil 秒级时间戳。</p>
     */
    public static final DefaultRedisScript<Long> LOCK = new DefaultRedisScript<Long>(
            "if redis.call('HSETNX', KEYS[1], '" + FIELD_LOCK_OWNER + "', ARGV[1]) == 1 then\n"
                    + "  redis.call('HSET', KEYS[1], '" + FIELD_LOCK_UNTIL + "', ARGV[2])\n"
                    + "  return 1\n"
                    + "end\n"
                    + "return 0",
            Long.class);

    /**
     * 释放锁：仅 owner 匹配时清空 {@code lockUntil}、移除 {@code lockOwner} 并设置兜底过期。
     *
     * <p>用于回源失败或提交异步任务失败，避免锁永久残留。</p>
     */
    public static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<Long>(
            "if redis.call('HGET', KEYS[1], '" + FIELD_LOCK_OWNER + "') == ARGV[1] then\n"
                    + "  redis.call('HSET', KEYS[1], '" + FIELD_LOCK_UNTIL + "', 0)\n"
                    + "  redis.call('HDEL', KEYS[1], '" + FIELD_LOCK_OWNER + "')\n"
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[2])\n"
                    + "end\n"
                    + "return 1",
            Long.class);

    private RockscacheScripts() {
    }
}
