package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostDataSourceProvider;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.core.produce.DefaultOutpostTemplate;
import com.outpost.core.produce.OutpostTemplate;
import com.outpost.core.produce.TransactionMessageCollector;
import com.outpost.starter.aop.OutboxMessageAspect;
import com.outpost.starter.aop.SpelValueResolver;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 生产路径装配：事务内消息聚合器、编程式 API 与注解切面。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
@EnableAspectJAutoProxy
public class OutpostProduceConfiguration {

    @Bean
    public TransactionMessageCollector outpostTransactionMessageCollector(
            JdbcOutpostMessageRepository repository,
            OutpostDataSourceProvider dataSourceProvider,
            OutpostProperties properties,
            OutpostMetrics metrics) {
        return new TransactionMessageCollector(
                repository, dataSourceProvider.getDataSource(), properties, metrics);
    }

    @Bean
    public OutpostTemplate outpostTemplate(TransactionMessageCollector collector,
                                           ObjectProvider<PlatformTransactionManager> transactionManagers,
                                           OutpostProperties properties) {
        PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable();
        if (transactionManager == null) {
            throw new OutpostConfigurationException(
                    "Outpost 未发现可用的 PlatformTransactionManager，无法在业务事务内注册消息");
        }
        return new DefaultOutpostTemplate(collector, transactionManager, properties);
    }

    @Bean
    public SpelValueResolver outpostSpelValueResolver() {
        return new SpelValueResolver();
    }

    @Bean
    public OutboxMessageAspect outboxMessageAspect(OutpostTemplate template,
                                                   SpelValueResolver resolver) {
        return new OutboxMessageAspect(template, resolver);
    }
}
