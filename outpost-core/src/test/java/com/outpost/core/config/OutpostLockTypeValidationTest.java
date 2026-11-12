package com.outpost.core.config;

import com.outpost.core.delivery.DeliveryHandlerRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutpostLockTypeValidationTest {

    private final OutpostPropertiesValidator validator = new OutpostPropertiesValidator();
    private final DeliveryHandlerRegistry registry =
            new DeliveryHandlerRegistry(Collections.emptyList());
    private final DataSource dataSource = (DataSource) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> null);

    @Test
    void defaultLockTypeIsRedis() {
        OutpostProperties properties = new OutpostProperties();
        assertDoesNotThrow(() -> validator.validateAndNormalize(properties, registry, dataSource));
        assertTrue("redis".equals(properties.getSchedule().getLockType()));
    }

    @Test
    void localLockTypeIsAccepted() {
        OutpostProperties properties = new OutpostProperties();
        properties.getSchedule().setLockType("local");
        assertDoesNotThrow(() -> validator.validateAndNormalize(properties, registry, dataSource));
    }

    @Test
    void unknownLockTypeFailsStartup() {
        OutpostProperties properties = new OutpostProperties();
        properties.getSchedule().setLockType("jdbc");
        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, registry, dataSource));
        assertTrue(error.getMessage().contains("lock-type"));
    }

    @Test
    void nullLockTypeFailsStartup() {
        OutpostProperties properties = new OutpostProperties();
        properties.getSchedule().setLockType(null);
        assertThrows(OutpostConfigurationException.class,
                () -> validator.validateAndNormalize(properties, registry, dataSource));
    }
}
