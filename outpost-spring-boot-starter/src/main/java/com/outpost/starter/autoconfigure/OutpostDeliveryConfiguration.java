package com.outpost.starter.autoconfigure;

import com.outpost.core.cleanup.OutpostCleanupScheduler;
import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostDataSourceProvider;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeadMessageListener;
import com.outpost.core.delivery.DeadMessageNotifier;
import com.outpost.core.delivery.DeliveryDispatcher;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.DeliveryOutcomeHandler;
import com.outpost.core.delivery.DefaultDeliveryOutcomeHandler;
import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.core.retry.BackoffPolicy;
import com.outpost.core.retry.DefaultBackoffPolicy;
import com.outpost.core.retry.DynamicBackoffPolicy;
import com.outpost.core.schedule.DistributedLock;
import com.outpost.core.schedule.LockingOutpostScheduler;
import com.outpost.core.schedule.OutpostScheduler;
import com.outpost.core.schedule.ScheduleRoundExecutor;
import com.outpost.starter.delivery.LoggingDeadMessageListener;
import com.outpost.starter.executor.TraceIdTaskDecorator;
import com.outpost.starter.scheduler.LocalDistributedLock;
import com.outpost.starter.scheduler.RedisDistributedLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 投递链路装配：退避、结果回写、线程池、调度器与启动校验。
 */
@Configuration(proxyBeanMethods = false)
public class OutpostDeliveryConfiguration {

    @Bean
    @ConditionalOnMissingBean(BackoffPolicy.class)
    public DynamicBackoffPolicy outpostBackoffPolicy(OutpostProperties properties) {
        return new DynamicBackoffPolicy(new DefaultBackoffPolicy(properties.getRetry().getBackoffSteps()));
    }

    @Bean
    public DeadMessageListener outpostLoggingDeadMessageListener() {
        return new LoggingDeadMessageListener();
    }

    @Bean
    public DeadMessageNotifier outpostDeadMessageNotifier(
            ObjectProvider<DeadMessageListener> listeners) {
        List<DeadMessageListener> resolved = new ArrayList<DeadMessageListener>();
        listeners.orderedStream().forEach(resolved::add);
        return new DeadMessageNotifier(resolved);
    }

    @Bean
    public DeliveryHandlerRegistry outpostDeliveryHandlerRegistry(
            ObjectProvider<DeliveryHandler> handlers) {
        List<DeliveryHandler> resolved = new ArrayList<DeliveryHandler>();
        handlers.orderedStream().forEach(resolved::add);
        return new DeliveryHandlerRegistry(resolved);
    }

    /**
     * 投递线程池：默认 2/4/1000 + CallerRunsPolicy，traceId 通过 TaskDecorator 透传。
     * 业务可用同名 bean {@code outpostDeliveryExecutor} 覆盖为自定义 Executor。
     */
    @Bean(name = "outpostDeliveryExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "outpostDeliveryExecutor")
    public ThreadPoolTaskExecutor outpostDeliveryExecutor(OutpostProperties properties) {
        OutpostProperties.Schedule schedule = properties.getSchedule();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(schedule.getCoreSize());
        executor.setMaxPoolSize(schedule.getMaxSize());
        executor.setQueueCapacity(schedule.getQueueCapacity());
        executor.setThreadNamePrefix("outpost-delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TraceIdTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public DeliveryOutcomeHandler outpostDeliveryOutcomeHandler(OutpostMessageRepository repository,
                                                                DeadMessageNotifier deadNotifier,
                                                                BackoffPolicy backoffPolicy,
                                                                OutpostMetrics metrics) {
        return new DefaultDeliveryOutcomeHandler(repository, deadNotifier, backoffPolicy, metrics);
    }

    @Bean
    public DeliveryDispatcher outpostDeliveryDispatcher(
            @Qualifier("outpostDeliveryExecutor") Executor executor,
            DeliveryHandlerRegistry registry,
            DeliveryOutcomeHandler outcomeHandler) {
        return new DeliveryDispatcher(executor, registry, outcomeHandler);
    }

    @Bean
    public ScheduleRoundExecutor outpostScheduleRoundExecutor(OutpostMessageRepository repository,
                                                              DeliveryDispatcher dispatcher,
                                                              OutpostProperties properties,
                                                              OutpostMetrics metrics) {
        return new ScheduleRoundExecutor(repository, dispatcher, properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public DistributedLock outpostDistributedLock(ObjectProvider<StringRedisTemplate> redisTemplates,
                                                  OutpostProperties properties) {
        if ("local".equals(properties.getSchedule().getLockType())) {
            return new LocalDistributedLock();
        }
        StringRedisTemplate redisTemplate = redisTemplates.getIfAvailable();
        if (redisTemplate == null) {
            throw new OutpostConfigurationException(
                    "outpost.schedule.lock-type=redis 但未发现 StringRedisTemplate；"
                            + "请引入 spring-boot-starter-data-redis 并配置 Redis 连接，"
                            + "或改用 outpost.schedule.lock-type=local（仅单实例部署）");
        }
        return new RedisDistributedLock(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(OutpostScheduler.class)
    public OutpostScheduler outpostRedisLockScheduler(DistributedLock lock,
                                                      ScheduleRoundExecutor roundExecutor,
                                                      OutpostProperties properties) {
        return new LockingOutpostScheduler(lock, roundExecutor, properties,
                properties.getSchedule().getRedisLockKey());
    }

    @Bean
    public OutpostStartupValidator outpostStartupValidator(ApplicationContext context,
                                                           OutpostProperties properties,
                                                           OutpostDataSourceProvider dataSourceProvider,
                                                           DeliveryHandlerRegistry registry) {
        return new OutpostStartupValidator(context, properties, dataSourceProvider, registry);
    }

    @Bean
    public OutpostLifecycle outpostLifecycle(ObjectProvider<OutpostScheduler> schedulers,
                                             OutpostProperties properties,
                                             OutpostCleanupScheduler cleanupScheduler) {
        List<OutpostScheduler> resolved = new ArrayList<OutpostScheduler>();
        schedulers.orderedStream().forEach(resolved::add);
        return new OutpostLifecycle(resolved, properties, cleanupScheduler);
    }
}
