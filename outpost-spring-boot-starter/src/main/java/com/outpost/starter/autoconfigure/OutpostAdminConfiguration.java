package com.outpost.starter.autoconfigure;

import com.outpost.core.admin.DefaultOutpostAdminService;
import com.outpost.core.admin.OutpostAdminService;
import com.outpost.core.cleanup.MessageCleanupTask;
import com.outpost.core.cleanup.OutpostCleanupScheduler;
import com.outpost.core.config.OutpostDataSourceProvider;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.config.OutpostPropertiesValidator;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.retry.DynamicBackoffPolicy;
import com.outpost.core.schedule.DistributedLock;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运维管理接口与清理任务装配。
 */
@Configuration(proxyBeanMethods = false)
public class OutpostAdminConfiguration {

    @Bean
    public OutpostAdminService outpostAdminService(JdbcOutpostMessageRepository repository,
                                                   OutpostProperties properties,
                                                   DynamicBackoffPolicy backoffPolicy,
                                                   OutpostDataSourceProvider dataSourceProvider,
                                                   DeliveryHandlerRegistry registry) {
        return new DefaultOutpostAdminService(repository, properties, backoffPolicy,
                new OutpostPropertiesValidator(), registry, dataSourceProvider.getDataSource());
    }

    @Bean
    public MessageCleanupTask outpostCleanupTask(JdbcOutpostMessageRepository repository,
                                                 OutpostProperties properties) {
        return new MessageCleanupTask(repository, properties);
    }

    /**
     * 每日固定间隔清理，复用分布式锁避免多实例重复清理。
     */
    @Bean
    public OutpostCleanupScheduler outpostCleanupScheduler(MessageCleanupTask task,
                                                           DistributedLock lock,
                                                           OutpostProperties properties) {
        return new OutpostCleanupScheduler(task, lock,
                properties.getSchedule().getRedisLockKey() + ":cleanup",
                properties.getCleanup().getFixedDelayMs());
    }
}
