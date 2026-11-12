package com.outpost.starter.autoconfigure;

import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.core.schedule.DistributedLock;
import com.outpost.core.schedule.ScheduleRoundExecutor;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import com.outpost.starter.scheduler.LocalDistributedLock;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 通用 Outbox 场景：关闭缓存处理器 + local 锁，验证完全无 Redis 时的装配与端到端投递。
 */
class OutpostGenericAutoConfigurationTest {

    private static final JdbcDataSource DATA_SOURCE = dataSource();
    private static final CopyOnWriteArrayList<String> DELIVERED = new CopyOnWriteArrayList<String>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutpostAutoConfiguration.class))
            .withPropertyValues(
                    "outpost.delivery-cache.enabled=false",
                    "outpost.schedule.lock-type=local",
                    "outpost.schedule.enabled=false")
            .withBean(DataSource.class, () -> DATA_SOURCE)
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(DATA_SOURCE))
            .withUserConfiguration(GenericHandlers.class);

    @BeforeEach
    void reset() {
        DELIVERED.clear();
        new JdbcTemplate(DATA_SOURCE).update("DELETE FROM outpost_message");
    }

    @Test
    void genericContextStartsWithoutRedis() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(StringRedisTemplate.class);
            assertThat(context.getBean(DistributedLock.class))
                    .isInstanceOf(LocalDistributedLock.class);
            assertThat(context.getBean(OutpostTemplate.class)).isNotNull();
            assertThat(context.getBean(DeliveryHandlerRegistry.class).bizTypes())
                    .containsExactly("order_paid");
        });
    }

    @Test
    void genericMessageIsDeliveredEndToEnd() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OutpostTemplate template = context.getBean(OutpostTemplate.class);
            template.executeInTransaction(() -> {
                template.registerMessage("order_paid", "order:1", "payload-1");
                return null;
            });

            JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outpost_message", Long.class))
                    .isEqualTo(1L);

            context.getBean(ScheduleRoundExecutor.class).runOnce();
            awaitDelivered(1);

            assertThat(DELIVERED).containsExactly("order:1");
            assertThat(jdbc.queryForObject("SELECT status FROM outpost_message", Integer.class))
                    .isEqualTo(2);
        });
    }

    @Test
    void rollbackLeavesNoMessageAndDelayedMessageIsNotDue() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OutpostTemplate template = context.getBean(OutpostTemplate.class);

            assertThrows(IllegalStateException.class, () -> template.executeInTransaction(() -> {
                template.registerMessage("order_paid", "order:rollback", "payload");
                throw new IllegalStateException("business rollback");
            }));

            JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outpost_message", Long.class))
                    .isEqualTo(0L);

            template.executeInTransaction(() -> {
                template.registerDelayedMessage("order_paid", "order:delayed", "payload", 3600000L);
                return null;
            });

            assertThat(context.getBean(JdbcOutpostMessageRepository.class).selectDue(100)).isEmpty();
        });
    }

    @Test
    void cacheEnabledWithoutRedisFailsStartup() {
        runner.withPropertyValues("outpost.delivery-cache.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("Redis");
                });
    }

    @Test
    void customDistributedLockRemovesRedisRequirement() {
        runner.withPropertyValues("outpost.schedule.lock-type=redis")
                .withBean(DistributedLock.class, LocalDistributedLock::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(StringRedisTemplate.class);
                });
    }

    private static void awaitDelivered(int expected) {
        for (int i = 0; i < 100; i++) {
            if (DELIVERED.size() >= expected) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("通用消息未在预期时间内投递，实际=" + DELIVERED);
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            message.append(current.getMessage() == null ? "" : current.getMessage()).append('\n');
            current = current.getCause();
        }
        return message.toString();
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outpost_generic_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("初始化 H2 消息表失败", e);
        }
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class GenericHandlers {

        @Bean
        DeliveryHandler orderPaidHandler() {
            return new DeliveryHandler() {
                @Override
                public String getBizType() {
                    return "order_paid";
                }

                @Override
                public void deliver(OutboxDeliveryMessage message) {
                    DELIVERED.add(message.getBizKey());
                }
            };
        }
    }
}
