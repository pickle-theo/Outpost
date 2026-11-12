package com.outpost.starter.monitor;

import com.outpost.core.message.OutpostMessageRepository;
import com.outpost.core.monitor.NoopOutpostMetrics;
import com.outpost.core.monitor.OutpostMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Constructor;

/**
 * 指标实现选择器：micrometer-core 是 optional 依赖，这里通过反射装配，
 * 保证 classpath 中不存在 Micrometer 时不会触发类加载错误，功能完全不受影响。
 */
public final class OutpostMetricsFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OutpostMetricsFactory.class);

    private static final String METER_REGISTRY_CLASS = "io.micrometer.core.instrument.MeterRegistry";
    private static final String MICROMETER_METRICS_CLASS =
            "com.outpost.starter.monitor.MicrometerOutpostMetrics";

    private OutpostMetricsFactory() {
    }

    public static OutpostMetrics create(ApplicationContext context,
                                        OutpostMessageRepository repository) {
        try {
            Class<?> registryType = Class.forName(METER_REGISTRY_CLASS, false,
                    OutpostMetricsFactory.class.getClassLoader());
            Object meterRegistry = context.getBeanProvider(registryType).getIfAvailable();
            if (meterRegistry == null) {
                LOG.info("Outpost 未发现 MeterRegistry，指标埋点降级为无埋点模式");
                return NoopOutpostMetrics.INSTANCE;
            }
            Class<?> metricsType = Class.forName(MICROMETER_METRICS_CLASS, true,
                    OutpostMetricsFactory.class.getClassLoader());
            Constructor<?> constructor = metricsType.getConstructor(
                    registryType, OutpostMessageRepository.class);
            return (OutpostMetrics) constructor.newInstance(meterRegistry, repository);
        } catch (ClassNotFoundException e) {
            LOG.info("Outpost 未检测到 micrometer-core，指标埋点降级为无埋点模式");
            return NoopOutpostMetrics.INSTANCE;
        } catch (ReflectiveOperationException e) {
            LOG.warn("Outpost 初始化 Micrometer 指标失败，降级为无埋点模式", e);
            return NoopOutpostMetrics.INSTANCE;
        }
    }
}
