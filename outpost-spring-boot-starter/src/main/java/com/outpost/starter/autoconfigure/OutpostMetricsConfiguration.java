package com.outpost.starter.autoconfigure;

import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.starter.monitor.OutpostMetricsFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标装配：不存在 MeterRegistry 或缺少 micrometer-core 时降级为无埋点。
 */
@Configuration(proxyBeanMethods = false)
public class OutpostMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutpostMetrics.class)
    public OutpostMetrics outpostMetrics(ApplicationContext context,
                                         OutpostMessageRepository repository) {
        return OutpostMetricsFactory.create(context, repository);
    }
}
