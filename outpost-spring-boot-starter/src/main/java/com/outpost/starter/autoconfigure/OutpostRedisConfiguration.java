package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostConfigurationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 模板装配：优先复用业务已有的 {@link StringRedisTemplate}，
 * 通过 StringRedisTemplate 同时兼容 Jedis / Lettuce。
 */
@Configuration(proxyBeanMethods = false)
public class OutpostRedisConfiguration {

    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate outpostStringRedisTemplate(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            throw new OutpostConfigurationException(
                    "Outpost 未发现 RedisConnectionFactory，缓存失效处理器与 Redis 调度锁均依赖 Redis；"
                            + "请引入 spring-boot-starter-data-redis 并配置 spring.redis.*，"
                            + "或自行声明 StringRedisTemplate bean");
        }
        return new StringRedisTemplate(connectionFactory);
    }
}
