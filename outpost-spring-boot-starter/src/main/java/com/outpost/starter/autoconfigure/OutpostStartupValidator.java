package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostDataSourceProvider;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.config.OutpostPropertiesValidator;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.schedule.OutpostScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 启动期强制校验：任何一项不满足都抛异常终止启动，不打印 WARN 后继续。
 */
public class OutpostStartupValidator implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(OutpostStartupValidator.class);

    private final ApplicationContext context;
    private final OutpostProperties properties;
    private final OutpostDataSourceProvider dataSourceProvider;
    private final DeliveryHandlerRegistry registry;

    public OutpostStartupValidator(ApplicationContext context,
                                   OutpostProperties properties,
                                   OutpostDataSourceProvider dataSourceProvider,
                                   DeliveryHandlerRegistry registry) {
        this.context = context;
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        DataSource dataSource = dataSourceProvider.getDataSource();
        List<String> warnings = new OutpostPropertiesValidator()
                .validateAndNormalize(properties, registry, dataSource);
        for (String warning : warnings) {
            LOG.warn("Outpost 启动配置告警：{}", warning);
        }

        if (context.getBeanNamesForType(PlatformTransactionManager.class, false, false).length == 0) {
            throw new OutpostConfigurationException(
                    "Outpost 未发现 PlatformTransactionManager，生产路径必须在业务事务内注册消息；"
                            + "请引入 spring-boot-starter-jdbc / -jpa 或自行声明事务管理器");
        }

        if (context.getBeanNamesForType(StringRedisTemplate.class, false, false).length == 0) {
            throw new OutpostConfigurationException(
                    "Outpost 未发现 StringRedisTemplate，缓存失效处理器与 Redis 调度锁均依赖 Redis；"
                            + "请引入 spring-boot-starter-data-redis 并配置 Redis 连接，"
                            + "或自行声明 StringRedisTemplate bean");
        }

        String configuredType = properties.getSchedule().getType();
        Map<String, ? extends OutpostScheduler> schedulers =
                context.getBeansOfType(OutpostScheduler.class, false, false);
        boolean matched = false;
        StringBuilder available = new StringBuilder();
        for (Map.Entry<String, ? extends OutpostScheduler> entry : schedulers.entrySet()) {
            if (available.length() > 0) {
                available.append(", ");
            }
            available.append(entry.getKey()).append('=').append(entry.getValue().getType());
            if (configuredType.equals(entry.getValue().getType())) {
                matched = true;
            }
        }
        if (!matched) {
            throw new OutpostConfigurationException(
                    "outpost.schedule.type=" + configuredType + " 没有匹配的 OutpostScheduler 实现；"
                            + "当前可用实现: [" + available + "]。"
                            + "请修正配置或提供自定义 OutpostScheduler bean");
        }
        LOG.info("Outpost 启动校验通过，schedule.type={}, handler bizTypes={}, dataSource={}",
                configuredType, registry.bizTypes(), dataSource.getClass().getSimpleName());
    }
}
