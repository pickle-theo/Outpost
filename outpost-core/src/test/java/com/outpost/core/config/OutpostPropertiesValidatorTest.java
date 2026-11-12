package com.outpost.core.config;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutpostPropertiesValidatorTest {

    private static final DataSource DATA_SOURCE = stubDataSource();

    private final OutpostPropertiesValidator validator = new OutpostPropertiesValidator();

    @Test
    void shouldRejectNullDataSource() {
        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(new OutpostProperties(), emptyRegistry(), null));

        assertTrue(error.getMessage().contains("outpost.data-source"));
        assertTrue(error.getMessage().contains("null"));
        assertTrue(error.getMessage().contains("DataSource"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-prefix", "a b", "a;b", "a`b"})
    void shouldRejectIllegalTablePrefix(String prefix) {
        OutpostProperties properties = validProperties();
        properties.setTablePrefix(prefix);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.table-prefix"));
        assertTrue(error.getMessage().contains(prefix));
        assertTrue(error.getMessage().contains("^[A-Za-z0-9_]{0,32}$"));
    }

    @ParameterizedTest
    @ValueSource(longs = {99L, 0L, -1L})
    void shouldRejectTooSmallFixedDelay(long fixedDelayMs) {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setFixedDelayMs(fixedDelayMs);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.schedule.fixed-delay-ms"));
        assertTrue(error.getMessage().contains("100ms"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1001, -5})
    void shouldRejectIllegalBatchSize(int batchSize) {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setBatchSize(batchSize);
        properties.getSchedule().setQueueCapacity(10 * batchSize + 1000);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.schedule.batch-size"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRejectIllegalMaxRetryCount(int maxRetryCount) {
        OutpostProperties properties = validProperties();
        properties.getRetry().setMaxRetryCount(maxRetryCount);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.retry.max-retry-count"));
    }

    static Stream<Arguments> illegalBackoffSteps() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of((Object) new long[0]),
                Arguments.of((Object) new long[]{1L, 3L, 3L}),
                Arguments.of((Object) new long[]{3L, 1L}),
                Arguments.of((Object) new long[]{1L, 0L, 2L})
        );
    }

    @ParameterizedTest
    @MethodSource("illegalBackoffSteps")
    void shouldRejectIllegalBackoffSteps(long[] steps) {
        OutpostProperties properties = validProperties();
        properties.getRetry().setBackoffSteps(steps);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.retry.backoff-steps"));
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 5})
    void shouldRejectTooSmallQueueCapacity(int queueCapacity) {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setQueueCapacity(queueCapacity);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.schedule.queue-capacity"));
        assertTrue(error.getMessage().contains("10 × batch-size"));
    }

    @Test
    void shouldRejectViolatedCouplingFormula() {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setBatchSize(1000);
        properties.getSchedule().setCoreSize(1);
        properties.getSchedule().setQueueCapacity(10000);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("耦合公式"));
        assertTrue(error.getMessage().contains("processing-timeout-seconds"));
    }

    @Test
    void shouldRejectTooSmallProcessingTimeoutForHandlerUpperBound() {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setProcessingTimeoutSeconds(10);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties,
                        registryWithMaxExecutionMillis(5000L), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.schedule.processing-timeout-seconds"));
        assertTrue(error.getMessage().contains("5000ms"));
    }

    @Test
    void shouldRejectIllegalHandlerType() {
        OutpostProperties properties = validProperties();
        properties.getDeliveryCache().setHandlerType("delete");

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.delivery-cache.handler-type"));
        assertTrue(error.getMessage().contains(CacheBizTypes.CACHE_DELETE));
        assertTrue(error.getMessage().contains(CacheBizTypes.CACHE_TAG_DELETE));
    }

    @Test
    void shouldSkipHandlerTypeValidationWhenCacheDisabled() {
        OutpostProperties properties = validProperties();
        properties.getDeliveryCache().setEnabled(false);
        properties.getDeliveryCache().setHandlerType("delete");

        assertDoesNotThrow(() -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));
    }

    @Test
    void shouldRejectTagModeWithoutReadClient() {
        OutpostProperties properties = validProperties();
        properties.getDeliveryCache().setHandlerType(CacheBizTypes.CACHE_TAG_DELETE);
        properties.getDeliveryCache().getTag().setReadClient(null);

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.delivery-cache.tag.read-client"));
        assertTrue(error.getMessage().contains("builtin"));
        assertTrue(error.getMessage().contains("external"));
        assertTrue(error.getMessage().contains("WRONGTYPE"));
    }

    @Test
    void shouldDisableDoubleDeleteAndWarnInTagMode() {
        OutpostProperties properties = validProperties();
        properties.getDeliveryCache().setHandlerType(CacheBizTypes.CACHE_TAG_DELETE);
        properties.getDeliveryCache().getTag().setReadClient("builtin");
        properties.getDeliveryCache().getDoubleDelete().setEnabled(true);

        List<String> warnings = validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("延迟双删已自动关闭"));
        assertFalse(properties.getDeliveryCache().getDoubleDelete().isEnabled());
    }

    @Test
    void shouldAcceptTagModeWithExplicitReadClient() {
        OutpostProperties properties = validProperties();
        properties.getDeliveryCache().setHandlerType(CacheBizTypes.CACHE_TAG_DELETE);
        properties.getDeliveryCache().getTag().setReadClient("external");

        List<String> warnings = validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE);

        assertNotNull(warnings);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void shouldRejectBlankScheduleType() {
        OutpostProperties properties = validProperties();
        properties.getSchedule().setType("   ");

        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, emptyRegistry(), DATA_SOURCE));

        assertTrue(error.getMessage().contains("outpost.schedule.type"));
    }

    @Test
    void shouldReturnEmptyWarningsWhenAllValid() {
        List<String> warnings = validator.validateAndNormalize(
                new OutpostProperties(), emptyRegistry(), DATA_SOURCE);

        assertNotNull(warnings);
        assertTrue(warnings.isEmpty());
    }

    private static OutpostProperties validProperties() {
        return new OutpostProperties();
    }

    private static DeliveryHandlerRegistry emptyRegistry() {
        return new DeliveryHandlerRegistry(Collections.<DeliveryHandler>emptyList());
    }

    private static DeliveryHandlerRegistry registryWithMaxExecutionMillis(final long maxExecutionMillis) {
        DeliveryHandler handler = new DeliveryHandler() {
            @Override
            public String getBizType() {
                return "slow_handler";
            }

            @Override
            public void deliver(OutboxDeliveryMessage message) {
                // no-op
            }

            @Override
            public long getMaxExecutionMillis() {
                return maxExecutionMillis;
            }
        };
        return new DeliveryHandlerRegistry(Arrays.<DeliveryHandler>asList(handler));
    }

    private static DataSource stubDataSource() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "stubDataSource";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                if (returnType == int.class) {
                    return Integer.valueOf(0);
                }
                if (returnType == long.class) {
                    return Long.valueOf(0L);
                }
                return null;
            }
        };
        return (DataSource) Proxy.newProxyInstance(
                OutpostPropertiesValidatorTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                handler);
    }
}
