package com.outpost.starter.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Outpost 自动装配入口。
 *
 * <p>{@code outpost.enabled=false} 时整个配置类及其 {@code @Import} 的配置都不会被解析，
 * 因此不会注册任何 Outpost bean，也不影响应用启动。</p>
 *
 * <p>编译目标为 Spring Boot 2.3.x 可用 API；Boot 3.x 通过
 * {@code META-INF/spring/...AutoConfiguration.imports} 做兼容性加载验证。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({DataSource.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "outpost", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutpostConfigurationProperties.class)
@Import({
        OutpostRepositoryConfiguration.class,
        OutpostRedisConfiguration.class,
        OutpostMetricsConfiguration.class,
        OutpostDeliveryConfiguration.class,
        OutpostCacheConfiguration.class,
        OutpostProduceConfiguration.class,
        OutpostAdminConfiguration.class
})
public class OutpostAutoConfiguration {
}
