package com.outpost.starter.cache;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试用 Redis 7 容器，静态复用，避免每个测试类重复启动。
 */
final class RedisTestSupport {

    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private static StringRedisTemplate template;

    private RedisTestSupport() {
    }

    static synchronized StringRedisTemplate template() {
        if (template == null) {
            REDIS.start();
            LettuceConnectionFactory factory =
                    new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
            factory.afterPropertiesSet();
            template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
        }
        return template;
    }

    static void flushAll() {
        template().execute((RedisCallback<Object>) connection -> {
            connection.flushDb();
            return null;
        });
    }
}
