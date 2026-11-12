package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostDataSourceProvider;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.starter.repository.JdbcOutpostMessageRepository;
import com.outpost.starter.repository.OutboxSchemaInitializer;
import com.outpost.starter.repository.OutboxTableGuard;
import com.outpost.starter.repository.TableNameResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源、表名解析、建表与持久层装配。
 */
@Configuration(proxyBeanMethods = false)
public class OutpostRepositoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutpostDataSourceProvider.class)
    public OutpostDataSourceProvider outpostDataSourceProvider(ApplicationContext context) {
        return new PrimaryDataSourceProvider(context);
    }

    @Bean
    @ConditionalOnMissingBean(TableNameResolver.class)
    public TableNameResolver outpostTableNameResolver(OutpostProperties properties) {
        return new TableNameResolver(properties.getTablePrefix());
    }

    @Bean
    public OutboxSchemaInitializer outpostSchemaInitializer(OutpostDataSourceProvider dataSourceProvider,
                                                            TableNameResolver tableNameResolver,
                                                            OutpostProperties properties) {
        OutboxSchemaInitializer initializer = new OutboxSchemaInitializer(
                dataSourceProvider.getDataSource(), tableNameResolver, properties.isAutoCreateTable());
        if (properties.isAutoCreateTable()) {
            initializer.initialize();
        }
        return initializer;
    }

    /**
     * 默认关闭自动建表：启动期不检查表是否存在，由 {@link OutboxTableGuard}
     * 在首次真正写入消息时给出「执行 DDL」的明确异常。
     */
    @Bean
    @ConditionalOnMissingBean(OutpostMessageRepository.class)
    public JdbcOutpostMessageRepository outpostMessageRepository(OutpostDataSourceProvider dataSourceProvider,
                                                                 TableNameResolver tableNameResolver,
                                                                 OutboxSchemaInitializer schemaInitializer,
                                                                 OutpostProperties properties) {
        OutboxTableGuard guard = properties.isAutoCreateTable()
                ? null
                : new OutboxTableGuard(tableNameResolver.messageTable(),
                        schemaInitializer::checkTableExists);
        return new JdbcOutpostMessageRepository(
                dataSourceProvider.getDataSource(), tableNameResolver, guard);
    }
}
