package com.outpost.starter.cache;

import com.outpost.core.cache.CacheInvalidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰度回落的同步直删实现：事务内调用时注册 {@code afterCommit} 回调，否则立即删除。
 *
 * <p>这是降级路径，删除失败只记录 ERROR，不向上抛出，避免影响业务主流程。</p>
 */
public class DirectCacheInvalidator implements CacheInvalidator {

    private static final Logger LOG = LoggerFactory.getLogger(DirectCacheInvalidator.class);

    private final StringRedisTemplate redis;

    public DirectCacheInvalidator(StringRedisTemplate redis) {
        if (redis == null) {
            throw new IllegalArgumentException("redis 不能为 null");
        }
        this.redis = redis;
    }

    @Override
    public void invalidate(String namespace, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        final List<String> targets = validKeys(keys);
        if (targets.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                }

                @Override
                public void resume() {
                }

                @Override
                public void flush() {
                }

                @Override
                public void beforeCommit(boolean readOnly) {
                }

                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCommit() {
                    delete(namespace, targets);
                }

                @Override
                public void afterCompletion(int status) {
                }
            });
        } else {
            delete(namespace, targets);
        }
    }

    private void delete(String namespace, List<String> keys) {
        LOG.warn("Outpost 缓存直删（灰度回落同步路径），namespace={}, keyCount={}", namespace, keys.size());
        try {
            redis.delete(keys);
        } catch (RuntimeException e) {
            LOG.error("Outpost 缓存直删失败，namespace={}, keyCount={}", namespace, keys.size(), e);
        }
    }

    private List<String> validKeys(List<String> keys) {
        List<String> targets = new ArrayList<String>(keys.size());
        for (String key : keys) {
            if (key != null && !key.trim().isEmpty()) {
                targets.add(key);
            }
        }
        return targets;
    }
}
