package com.outpost.starter.autoconfigure;

import com.outpost.core.cache.CacheInvalidator;
import com.outpost.core.cache.OutboxCacheInvalidator;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.starter.cache.CacheDeleteHandler;
import com.outpost.starter.cache.CacheTagDeleteHandler;
import com.outpost.starter.cache.DirectCacheInvalidator;
import com.outpost.starter.cache.RockscacheReadClient;
import com.outpost.starter.cache.TimeoutRedisExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executor;

/**
 * 缓存场景装配：按 {@code outpost.delivery-cache.handler-type} 二选一注册处理器，
 * 灰度白名单路由与可选 rockscache 兼容读客户端。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "outpost.delivery-cache", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import(OutpostCacheConfiguration.TagModeConfiguration.class)
public class OutpostCacheConfiguration {

    @Bean
    public TimeoutRedisExecutor outpostTimeoutRedisExecutor(OutpostProperties properties) {
        return new TimeoutRedisExecutor(properties.getDeliveryCache().getRedisTimeoutMs());
    }

    @Bean
    @ConditionalOnProperty(prefix = "outpost.delivery-cache", name = "handler-type",
            havingValue = "cache_delete", matchIfMissing = true)
    public CacheDeleteHandler outpostCacheDeleteHandler(StringRedisTemplate redisTemplate,
                                                        TimeoutRedisExecutor timeoutExecutor) {
        return new CacheDeleteHandler(redisTemplate, timeoutExecutor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "outpost.delivery-cache", name = "handler-type",
            havingValue = "cache_tag_delete")
    public CacheTagDeleteHandler outpostCacheTagDeleteHandler(StringRedisTemplate redisTemplate,
                                                              TimeoutRedisExecutor timeoutExecutor,
                                                              OutpostProperties properties) {
        return new CacheTagDeleteHandler(redisTemplate, timeoutExecutor, properties);
    }

    /**
     * 灰度回落实现：afterCommit 同步 DEL，对 tag 读路径同样安全（整 key 删除按 miss 回源）。
     */
    @Bean
    public DirectCacheInvalidator outpostDirectCacheInvalidator(StringRedisTemplate redisTemplate) {
        return new DirectCacheInvalidator(redisTemplate);
    }

    @Bean
    @Primary
    public CacheInvalidator outpostCacheInvalidator(
            OutpostTemplate template,
            @Qualifier("outpostDirectCacheInvalidator") DirectCacheInvalidator directFallback,
            OutpostProperties properties) {
        return new OutboxCacheInvalidator(template, directFallback, properties);
    }

    /**
     * tag 模式专属装配：read-client=builtin 时提供 Java 读客户端，external 时不装配读侧。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "outpost.delivery-cache", name = "handler-type",
            havingValue = "cache_tag_delete")
    public static class TagModeConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "outpost.delivery-cache.tag", name = "read-client",
                havingValue = "builtin")
        public RockscacheReadClient outpostRockscacheReadClient(
                StringRedisTemplate redisTemplate,
                OutpostProperties properties,
                @Qualifier("outpostDeliveryExecutor") Executor asyncExecutor) {
            return new RockscacheReadClient(redisTemplate, properties, asyncExecutor);
        }
    }
}
