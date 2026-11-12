package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.schedule.DistributedLock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * 判断当前配置是否真的需要 Redis。
 *
 * <p>只有「启用内置缓存处理器」或「使用 Redis 分布式锁」时才需要
 * {@link org.springframework.data.redis.core.StringRedisTemplate}。
 * 纯通用 Outbox（缓存关闭 + local 锁）可以完全不依赖 Redis；
 * 业务提供了自定义 {@link DistributedLock} 时同样不再强制 Redis。</p>
 */
public final class OutpostRedisRequirement {

    private static final String DEFAULT_LOCK_BEAN = "outpostDistributedLock";

    private OutpostRedisRequirement() {
    }

    public static boolean isRequired(OutpostProperties properties) {
        return properties.getDeliveryCache().isEnabled()
                || !"local".equals(properties.getSchedule().getLockType());
    }

    /**
     * 配置类解析阶段尚不存在绑定的 properties bean，直接从 Environment 读取。
     * 同时兼容 kebab-case 与 camelCase 两种写法。
     */
    public static boolean isRequired(Environment environment) {
        Boolean cacheEnabled = environment.getProperty(
                "outpost.delivery-cache.enabled", Boolean.class);
        if (cacheEnabled == null) {
            cacheEnabled = environment.getProperty("outpost.deliveryCache.enabled", Boolean.class);
        }
        boolean cacheRequired = cacheEnabled == null || cacheEnabled;

        String lockType = environment.getProperty("outpost.schedule.lock-type");
        if (lockType == null) {
            lockType = environment.getProperty("outpost.schedule.lockType");
        }
        return cacheRequired || !"local".equals(lockType);
    }

    /**
     * 业务是否提供了非 SDK 默认的分布式锁实现。
     */
    public static boolean hasCustomLock(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return false;
        }
        for (String name : beanFactory.getBeanNamesForType(DistributedLock.class, false, false)) {
            if (!DEFAULT_LOCK_BEAN.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
