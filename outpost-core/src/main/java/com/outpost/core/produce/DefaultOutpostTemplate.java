package com.outpost.core.produce;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.config.OutpostProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * 默认的 {@link OutpostTemplate} 实现，负责参数校验与缓存 key 展开，实际落库委托给
 * {@link TransactionMessageCollector}。
 */
public class DefaultOutpostTemplate implements OutpostTemplate {

    private final TransactionMessageCollector collector;
    private final PlatformTransactionManager transactionManager;
    private final OutpostProperties properties;

    public DefaultOutpostTemplate(TransactionMessageCollector collector,
                                  PlatformTransactionManager transactionManager,
                                  OutpostProperties properties) {
        if (collector == null) {
            throw new IllegalArgumentException("collector 不能为 null");
        }
        if (transactionManager == null) {
            throw new IllegalArgumentException("transactionManager 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        this.collector = collector;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    @Override
    public <T> T executeInTransaction(Supplier<T> businessAction) {
        if (businessAction == null) {
            throw new IllegalArgumentException("businessAction 不能为 null");
        }
        return new TransactionTemplate(transactionManager).execute(status -> businessAction.get());
    }

    @Override
    public void registerMessage(String bizType, String bizKey, String payload) {
        validateBizTypeAndKey(bizType, bizKey);
        collector.register(MessageRegistration.normal(bizType, bizKey, payload,
                0L, properties.getRetry().getMaxRetryCount()));
    }

    @Override
    public void registerDelayedMessage(String bizType, String bizKey, String payload, long delayMills) {
        validateBizTypeAndKey(bizType, bizKey);
        if (delayMills < 0) {
            throw new IllegalArgumentException("delayMills 不能为负数: " + delayMills);
        }
        collector.register(MessageRegistration.normal(bizType, bizKey, payload,
                delayMills, properties.getRetry().getMaxRetryCount()));
    }

    @Override
    public void registerCacheInvalidate(String namespace, List<String> cacheKeys) {
        registerCacheInvalidate(namespace, cacheKeys, -1L);
    }

    @Override
    public void registerCacheInvalidate(String namespace, List<String> cacheKeys,
                                        long doubleDeleteDelayMills) {
        if (namespace == null || namespace.trim().isEmpty()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return;
        }
        for (String cacheKey : cacheKeys) {
            if (cacheKey == null || cacheKey.trim().isEmpty()) {
                throw new IllegalArgumentException("cacheKey 不能为空，namespace=" + namespace);
            }
        }

        String handlerType = properties.getDeliveryCache().getHandlerType();
        boolean tagMode = CacheBizTypes.CACHE_TAG_DELETE.equals(handlerType);
        String bizType = tagMode ? CacheBizTypes.CACHE_TAG_DELETE : CacheBizTypes.CACHE_DELETE;
        boolean doubleDelete = !tagMode
                && properties.getDeliveryCache().getDoubleDelete().isEnabled();
        long globalDelay = properties.getDeliveryCache().getDoubleDelete().getDelayMillis();
        long doubleDeleteDelay = doubleDeleteDelayMills >= 0L ? doubleDeleteDelayMills : globalDelay;
        int maxRetry = properties.getRetry().getMaxRetryCount();

        for (String cacheKey : cacheKeys) {
            String bizKey = namespace + ":" + cacheKey;
            collector.register(MessageRegistration.normal(bizType, bizKey, cacheKey, 0L, maxRetry));
            if (doubleDelete) {
                collector.register(MessageRegistration.auxiliary(bizType, bizKey, cacheKey,
                        doubleDeleteDelay));
            }
        }
    }

    private void validateBizTypeAndKey(String bizType, String bizKey) {
        if (bizType == null || bizType.trim().isEmpty()) {
            throw new IllegalArgumentException("bizType 不能为空");
        }
        if (bizKey == null || bizKey.trim().isEmpty()) {
            throw new IllegalArgumentException("bizKey 不能为空");
        }
    }
}
