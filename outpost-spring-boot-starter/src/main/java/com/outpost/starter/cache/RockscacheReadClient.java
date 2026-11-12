package com.outpost.starter.cache;

import com.outpost.core.config.OutpostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * rockscache 兼容的弱一致读客户端。
 *
 * <p>缓存条目使用 Hash 语义（{@code value}/{@code lockUntil}/{@code lockOwner}），与
 * {@code cache_tag_delete} 处理器共享同一套协议：</p>
 * <ul>
 *   <li>命中且未被标记失效：直接返回值；</li>
 *   <li>命中但已被标记失效（{@code lockUntil <= now}）：立即返回旧值，抢到锁后异步回源；</li>
 *   <li>未命中：抢锁成功则同步回源并按 TTL 规则写回；抢锁失败则短暂等待后重试（有上界）；</li>
 *   <li>Redis 异常：记录 ERROR 并降级为直接调用 loader，保证读路径可用。</li>
 * </ul>
 *
 * <p>写回一律经过 {@code lockOwner} 校验，旧的回源结果不会覆盖新值。</p>
 */
public class RockscacheReadClient {

    private static final Logger LOG = LoggerFactory.getLogger(RockscacheReadClient.class);

    /** 抢锁失败后的最大重试次数，约 50 × lockSleepMillis（默认 5s） */
    private static final int MAX_LOCK_ATTEMPTS = 50;

    private final StringRedisTemplate redis;
    private final Executor asyncExecutor;
    private final int delaySeconds;
    private final long lockExpireSeconds;
    private final long lockSleepMillis;
    private final long emptyExpireSeconds;
    private final double randomExpireAdjustment;
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<String, Boolean>();

    public RockscacheReadClient(StringRedisTemplate redis,
                                OutpostProperties properties,
                                Executor asyncExecutor) {
        if (redis == null) {
            throw new IllegalArgumentException("redis 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        if (asyncExecutor == null) {
            throw new IllegalArgumentException("asyncExecutor 不能为 null");
        }
        this.redis = redis;
        this.asyncExecutor = asyncExecutor;
        OutpostProperties.Tag tag = properties.getDeliveryCache().getTag();
        this.delaySeconds = Math.max(0, tag.getDelaySeconds());
        this.lockExpireSeconds = Math.max(1L, (tag.getLockExpireMillis() + 999L) / 1000L);
        this.lockSleepMillis = Math.max(1L, tag.getLockSleepMillis());
        this.emptyExpireSeconds = Math.max(1L, tag.getEmptyExpireSeconds());
        this.randomExpireAdjustment = Math.max(0D, tag.getRandomExpireAdjustment());
    }

    /**
     * 单个 key 的弱一致读取。
     *
     * @param key           Redis key
     * @param expireSeconds 业务期望的缓存过期时间（秒）
     * @param loader        回源函数
     * @return 缓存值；Redis 不可用时直接返回 loader 的结果
     */
    public String fetch(String key, long expireSeconds, Supplier<String> loader) {
        validateReadArgs(key, loader);
        HashState state = read(key);
        if (state == null) {
            return loader.get();
        }
        if (state.value != null) {
            if (state.lockUntil <= nowSeconds()) {
                refreshAsync(key, expireSeconds, loader);
            }
            return state.value;
        }
        return loadOnMiss(key, expireSeconds, loader);
    }

    /**
     * 批量弱一致读取，返回顺序与入参 keys 一致。
     *
     * <p>命中但被标记失效的 key 返回旧值并各自异步回源；未命中的 key 收集后一次性调用
     * {@code batchLoader}，再逐个按 owner 校验写回。</p>
     */
    public Map<String, String> fetchBatch(List<String> keys,
                                          long expireSeconds,
                                          Function<List<String>, Map<String, String>> batchLoader) {
        if (keys == null) {
            throw new IllegalArgumentException("keys 不能为 null");
        }
        if (batchLoader == null) {
            throw new IllegalArgumentException("batchLoader 不能为 null");
        }
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("缓存 key 不能为空");
            }
        }

        Map<String, String> values = new HashMap<String, String>();
        List<String> missing = new ArrayList<String>();
        for (String key : keys) {
            HashState state = read(key);
            if (state != null && state.value != null) {
                values.put(key, state.value);
                if (state.lockUntil <= nowSeconds()) {
                    final String refreshKey = key;
                    refreshAsync(key, expireSeconds,
                            () -> loadSingleWithBatch(refreshKey, batchLoader));
                }
            } else {
                missing.add(key);
            }
        }

        if (!missing.isEmpty()) {
            Map<String, String> lockOwners = new HashMap<String, String>();
            for (String key : missing) {
                String owner = newOwner();
                Boolean locked = tryLock(key, owner);
                if (Boolean.TRUE.equals(locked)) {
                    lockOwners.put(key, owner);
                }
            }
            Map<String, String> loaded;
            try {
                loaded = batchLoader.apply(missing);
            } catch (RuntimeException e) {
                for (Map.Entry<String, String> entry : lockOwners.entrySet()) {
                    releaseLockQuietly(entry.getKey(), entry.getValue());
                }
                throw e;
            }
            for (String key : missing) {
                String value = loaded == null ? null : loaded.get(key);
                values.put(key, value);
                String owner = lockOwners.get(key);
                if (owner != null) {
                    writeBackQuietly(key, owner, value, expireSeconds);
                }
            }
        }

        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : keys) {
            result.put(key, values.get(key));
        }
        return result;
    }

    /**
     * 不做任何锁校验的直接读取，key 不存在时返回 null。
     */
    public String rawGet(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为 null");
        }
        Object value = hashOps().get(key, RockscacheScripts.FIELD_VALUE);
        return value == null ? null : value.toString();
    }

    /**
     * 直接写入缓存：写入 value 与 lockUntil，移除 lockOwner 并设置过期时间。
     */
    public void rawSet(String key, String value, long expireSeconds) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为 null");
        }
        long expire = Math.max(1L, expireSeconds);
        hashOps().put(key, RockscacheScripts.FIELD_VALUE, value == null ? "" : value);
        hashOps().put(key, RockscacheScripts.FIELD_LOCK_UNTIL,
                String.valueOf(nowSeconds() + lockExpireSeconds));
        hashOps().delete(key, RockscacheScripts.FIELD_LOCK_OWNER);
        redis.expire(key, expire, java.util.concurrent.TimeUnit.SECONDS);
    }

    private String loadOnMiss(String key, long expireSeconds, Supplier<String> loader) {
        for (int attempt = 0; attempt < MAX_LOCK_ATTEMPTS; attempt++) {
            String owner = newOwner();
            Boolean locked = tryLock(key, owner);
            if (locked == null) {
                return loader.get();
            }
            if (Boolean.TRUE.equals(locked)) {
                String value;
                try {
                    value = loader.get();
                } catch (RuntimeException e) {
                    releaseLockQuietly(key, owner);
                    throw e;
                }
                writeBackQuietly(key, owner, value, expireSeconds);
                return value;
            }
            sleepBeforeRetry(key);
        }
        LOG.warn("Outpost rockscache 读取等待回源锁超限，直接回源且不写缓存，key={}", key);
        return loader.get();
    }

    private String loadSingleWithBatch(String key,
                                       Function<List<String>, Map<String, String>> batchLoader) {
        Map<String, String> loaded = batchLoader.apply(Collections.singletonList(key));
        return loaded == null ? null : loaded.get(key);
    }

    private void refreshAsync(final String key, final long expireSeconds, final Supplier<String> loader) {
        if (refreshing.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        final String owner = newOwner();
        Boolean locked = tryLock(key, owner);
        if (!Boolean.TRUE.equals(locked)) {
            refreshing.remove(key);
            return;
        }
        try {
            asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String value = loader.get();
                        writeBackQuietly(key, owner, value, expireSeconds);
                    } catch (RuntimeException e) {
                        LOG.error("Outpost rockscache 异步回源失败，key={}", key, e);
                        releaseLockQuietly(key, owner);
                    } finally {
                        refreshing.remove(key);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Outpost rockscache 提交异步回源失败，key={}", key, e);
            refreshing.remove(key);
            releaseLockQuietly(key, owner);
        }
    }

    private void writeBackQuietly(String key, String owner, String value, long expireSeconds) {
        String cacheValue = value == null ? "" : value;
        long ttl = cacheValue.isEmpty()
                ? emptyExpireSeconds
                : ttlSeconds(expireSeconds);
        long lockUntil = nowSeconds() + ttl;
        try {
            Long written = redis.execute(RockscacheScripts.SET_VALUE,
                    Collections.singletonList(key),
                    owner, cacheValue, String.valueOf(lockUntil), String.valueOf(ttl));
            LOG.debug("Outpost rockscache 写回缓存，key={}, ttl={}, written={}", key, ttl, written);
        } catch (RuntimeException e) {
            LOG.error("Outpost rockscache 写回缓存失败，key={}", key, e);
        }
    }

    private long ttlSeconds(long expireSeconds) {
        long random = (long) (expireSeconds * randomExpireAdjustment * Math.random());
        return Math.max(1L, expireSeconds - delaySeconds - random);
    }

    private HashState read(String key) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            Object value = raw.get(RockscacheScripts.FIELD_VALUE);
            Object lockUntil = raw.get(RockscacheScripts.FIELD_LOCK_UNTIL);
            return new HashState(value == null ? null : value.toString(), parseLong(lockUntil));
        } catch (RuntimeException e) {
            LOG.error("Outpost rockscache 读取缓存异常，降级直连 loader，key={}", key, e);
            return null;
        }
    }

    /**
     * @return true 抢到锁；false 未抢到；null 表示 Redis 异常
     */
    private Boolean tryLock(String key, String owner) {
        try {
            Long result = redis.execute(RockscacheScripts.LOCK,
                    Collections.singletonList(key),
                    owner, String.valueOf(nowSeconds() + lockExpireSeconds));
            return result != null && result > 0L;
        } catch (RuntimeException e) {
            LOG.error("Outpost rockscache 抢锁异常，key={}", key, e);
            return null;
        }
    }

    private void releaseLockQuietly(String key, String owner) {
        try {
            redis.execute(RockscacheScripts.RELEASE_LOCK,
                    Collections.singletonList(key),
                    owner, String.valueOf(lockExpireSeconds));
        } catch (RuntimeException e) {
            LOG.warn("Outpost rockscache 释放回源锁失败，等待兜底过期，key={}", key, e);
        }
    }

    private void sleepBeforeRetry(String key) {
        try {
            Thread.sleep(lockSleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Outpost rockscache 等待回源锁被中断，key=" + key, e);
        }
    }

    private HashOperations<String, String, String> hashOps() {
        return redis.opsForHash();
    }

    private void validateReadArgs(String key, Supplier<String> loader) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存 key 不能为空");
        }
        if (loader == null) {
            throw new IllegalArgumentException("loader 不能为 null");
        }
    }

    private static String newOwner() {
        return UUID.randomUUID().toString();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static final class HashState {

        private final String value;
        private final long lockUntil;

        private HashState(String value, long lockUntil) {
            this.value = value;
            this.lockUntil = lockUntil;
        }
    }
}
