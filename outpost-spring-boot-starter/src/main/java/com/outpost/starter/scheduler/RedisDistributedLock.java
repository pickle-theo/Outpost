package com.outpost.starter.scheduler;

import com.outpost.core.schedule.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis 的分布式锁：{@code SET key value NX PX ttl} 加锁，Lua 校验 value 后删除。
 *
 * <p>无续期、无可重入、无 Redlock；锁超时最多导致重复投递，由 handler 幂等兜底。</p>
 */
public class RedisDistributedLock implements DistributedLock {

    private static final Logger LOG = LoggerFactory.getLogger(RedisDistributedLock.class);

    private static final long WARN_INTERVAL_MILLIS = 30000L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<Long>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Map<String, String> lockValues = new ConcurrentHashMap<String, String>();
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate 不能为 null");
        }
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String key, long ttlMillis) {
        String value = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, value, ttlMillis, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                lockValues.put(key, value);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            warnThrottled("Outpost 获取 Redis 调度锁异常，本轮按未获取处理，key={}", key, e);
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        String value = lockValues.remove(key);
        if (value == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
        } catch (RuntimeException e) {
            warnThrottled("Outpost 释放 Redis 调度锁异常，将等待锁自动过期，key={}", key, e);
        }
    }

    private void warnThrottled(String message, String key, RuntimeException error) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
            LOG.warn(message, key, error);
        }
    }
}
