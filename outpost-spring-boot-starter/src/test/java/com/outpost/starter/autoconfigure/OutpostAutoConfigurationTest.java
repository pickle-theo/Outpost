package com.outpost.starter.autoconfigure;

import com.outpost.core.admin.OutpostAdminService;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.core.schedule.OutpostScheduler;
import com.outpost.starter.monitor.MicrometerOutpostMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配上下文加载测试：验证总开关、启动期校验、指标降级与 Boot 2.x 装配链路。
 */
class OutpostAutoConfigurationTest {

    private static final JdbcDataSource DATA_SOURCE = dataSource();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutpostAutoConfiguration.class))
            .withPropertyValues("outpost.schedule.enabled=false")
            .withBean(DataSource.class, () -> DATA_SOURCE)
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(DATA_SOURCE))
            .withBean(StringRedisTemplate.class,
                    () -> new StringRedisTemplate(Mockito.mock(RedisConnectionFactory.class)));

    @Test
    void disabledDoesNotRegisterAnyOutpostBean() {
        runner.withPropertyValues("outpost.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutpostProperties.class);
            assertThat(context).doesNotHaveBean(OutpostTemplate.class);
            assertThat(context).doesNotHaveBean(OutpostScheduler.class);
            assertThat(context).doesNotHaveBean(OutpostAdminService.class);
            assertThat(context).doesNotHaveBean(OutpostMetrics.class);
        });
    }

    @Test
    void loadsFullContextWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutpostProperties.class);
            assertThat(context).hasSingleBean(OutpostTemplate.class);
            assertThat(context).hasSingleBean(OutpostAdminService.class);
            assertThat(context).hasSingleBean(OutpostScheduler.class);
            assertThat(context).hasSingleBean(OutpostMetrics.class);
            assertThat(context.getBean(OutpostMetrics.class).getClass().getSimpleName())
                    .isEqualTo("NoopOutpostMetrics");
            assertThat(context.getBean(OutpostProperties.class).isAutoCreateTable()).isFalse();
        });
    }

    @Test
    void illegalTablePrefixFailsStartup() {
        runner.withPropertyValues("outpost.table-prefix=bad;drop")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unknownScheduleTypeFailsStartup() {
        runner.withPropertyValues("outpost.schedule.type=no-such-scheduler")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("没有匹配的 OutpostScheduler");
                });
    }

    @Test
    void duplicateBizTypeFailsStartup() {
        runner.withUserConfiguration(DuplicateHandlerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("重复的 DeliveryHandler bizType");
                });
    }

    @Test
    void couplingFormulaViolationFailsStartup() {
        runner.withPropertyValues("outpost.schedule.batch-size=1000",
                        "outpost.schedule.processing-timeout-seconds=300",
                        "outpost.schedule.core-size=4",
                        "outpost.schedule.queue-capacity=10000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("耦合公式");
                });
    }

    @Test
    void meterRegistryEnablesMicrometerMetrics() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutpostMetrics.class))
                            .isInstanceOf(MicrometerOutpostMetrics.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    OutpostMetrics metrics = context.getBean(OutpostMetrics.class);
                    metrics.onMessageProduced(3);
                    metrics.onDeliverySuccess("cache_delete", 5L);
                    metrics.onDeliveryFail("cache_delete", 7L);
                    assertThat(registry.find("outpost.message.produce.total").counter().count())
                            .isEqualTo(3D);
                    assertThat(registry.find("outpost.message.delivery.success.total").counter())
                            .isNotNull();
                    assertThat(registry.find("outpost.message.delivery.fail.total").counter())
                            .isNotNull();
                    assertThat(registry.find("outpost.message.dead.gauge").gauge()).isNotNull();
                    assertThat(registry.find("outpost.message.redelivery.ratio").gauge()).isNotNull();
                    assertThat(registry.find("outpost.schedule.duration.ms").timer()).isNotNull();
                    assertThat(registry.find("outpost.delivery.duration.ms").timer()).isNotNull();
                    assertThat(registry.find("outpost.retry.count.distribution").summary()).isNotNull();
                });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        StringBuilder message = new StringBuilder();
        while (current != null) {
            message.append(current.getMessage() == null ? "" : current.getMessage()).append('\n');
            current = current.getCause();
        }
        return message.toString();
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost-autoconfigure;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateHandlerConfiguration {

        @Bean
        DeliveryHandler firstHandler() {
            return handler();
        }

        @Bean
        DeliveryHandler secondHandler() {
            return handler();
        }

        private DeliveryHandler handler() {
            return new DeliveryHandler() {
                @Override
                public String getBizType() {
                    return "duplicated";
                }

                @Override
                public void deliver(com.outpost.core.delivery.OutboxDeliveryMessage message) {
                    // no-op
                }
            };
        }
    }
}
