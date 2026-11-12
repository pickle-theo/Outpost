package com.outpost.core.delivery;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryHandlerRegistryTest {

    @Test
    void shouldFindRegisteredHandler() {
        SimpleHandler handler = new SimpleHandler("cache_delete");
        DeliveryHandlerRegistry registry =
                new DeliveryHandlerRegistry(Collections.<DeliveryHandler>singletonList(handler));

        assertTrue(registry.find("cache_delete").isPresent());
        assertSame(handler, registry.find("cache_delete").get());
        assertSame(handler, registry.getRequired("cache_delete"));
        assertFalse(registry.find("unknown").isPresent());
    }

    @Test
    void shouldThrowWhenRequiredHandlerMissing() {
        DeliveryHandlerRegistry registry =
                new DeliveryHandlerRegistry(Collections.<DeliveryHandler>emptyList());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.getRequired("absent_type"));
        assertTrue(error.getMessage().contains("absent_type"));
    }

    @Test
    void shouldRejectDuplicateBizType() {
        DeliveryHandler first = new DuplicateHandlerA();
        DeliveryHandler second = new DuplicateHandlerB();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DeliveryHandlerRegistry(Arrays.asList(first, second)));

        assertTrue(error.getMessage().contains("dup_type"));
        assertTrue(error.getMessage().contains("DuplicateHandlerA"));
        assertTrue(error.getMessage().contains("DuplicateHandlerB"));
    }

    @Test
    void shouldRejectBlankBizType() {
        IllegalStateException blankError = assertThrows(IllegalStateException.class,
                () -> new DeliveryHandlerRegistry(
                        Collections.<DeliveryHandler>singletonList(new SimpleHandler("  "))));
        assertTrue(blankError.getMessage().contains("bizType"));

        IllegalStateException nullError = assertThrows(IllegalStateException.class,
                () -> new DeliveryHandlerRegistry(
                        Collections.<DeliveryHandler>singletonList(new SimpleHandler(null))));
        assertTrue(nullError.getMessage().contains("bizType"));
    }

    @Test
    void shouldRejectNullHandler() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DeliveryHandlerRegistry(
                        Collections.<DeliveryHandler>singletonList(null)));
        assertTrue(error.getMessage().contains("null"));
    }

    @Test
    void shouldExposeAllHandlersAndBizTypes() {
        DeliveryHandlerRegistry registry = new DeliveryHandlerRegistry(Arrays.asList(
                new SimpleHandler("cache_delete"),
                new SimpleHandler("cache_tag_delete")));

        assertEquals(2, registry.all().size());
        assertEquals(2, registry.bizTypes().size());
        assertTrue(registry.bizTypes().contains("cache_delete"));
        assertTrue(registry.bizTypes().contains("cache_tag_delete"));
    }

    private static class SimpleHandler implements DeliveryHandler {

        private final String bizType;

        SimpleHandler(String bizType) {
            this.bizType = bizType;
        }

        @Override
        public String getBizType() {
            return bizType;
        }

        @Override
        public void deliver(OutboxDeliveryMessage message) {
            // no-op
        }
    }

    private static class DuplicateHandlerA extends SimpleHandler {

        DuplicateHandlerA() {
            super("dup_type");
        }
    }

    private static class DuplicateHandlerB extends SimpleHandler {

        DuplicateHandlerB() {
            super("dup_type");
        }
    }
}
