package com.outpost.core.cache;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.produce.OutpostTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxCacheInvalidatorTest {

    @Test
    void emptyWhitelistShouldRouteToOutboxTemplate() {
        OutpostProperties properties = new OutpostProperties();
        RecordingTemplate template = new RecordingTemplate();
        RecordingInvalidator fallback = new RecordingInvalidator();
        OutboxCacheInvalidator invalidator = new OutboxCacheInvalidator(template, fallback, properties);

        invalidator.invalidate("order", Arrays.asList("k1", "k2"));

        assertEquals(1, template.calls);
        assertEquals("order", template.namespace);
        assertEquals(Arrays.asList("k1", "k2"), template.keys);
        assertEquals(0, fallback.calls);
    }

    @Test
    void nullWhitelistShouldBeTreatedAsEmpty() {
        OutpostProperties properties = new OutpostProperties();
        properties.getDeliveryCache().setEnabledNamespaces(null);
        RecordingTemplate template = new RecordingTemplate();
        RecordingInvalidator fallback = new RecordingInvalidator();
        OutboxCacheInvalidator invalidator = new OutboxCacheInvalidator(template, fallback, properties);

        invalidator.invalidate("user", Collections.singletonList("k1"));

        assertEquals(1, template.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void whitelistedNamespaceShouldRouteToOutboxTemplate() {
        OutpostProperties properties = new OutpostProperties();
        properties.getDeliveryCache().setEnabledNamespaces(Arrays.asList("order", "user"));
        RecordingTemplate template = new RecordingTemplate();
        RecordingInvalidator fallback = new RecordingInvalidator();
        OutboxCacheInvalidator invalidator = new OutboxCacheInvalidator(template, fallback, properties);

        invalidator.invalidate("user", Collections.singletonList("k1"));

        assertEquals(1, template.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void nonWhitelistedNamespaceShouldFallBackToDirectDelete() {
        OutpostProperties properties = new OutpostProperties();
        properties.getDeliveryCache().setEnabledNamespaces(Collections.singletonList("order"));
        RecordingTemplate template = new RecordingTemplate();
        RecordingInvalidator fallback = new RecordingInvalidator();
        OutboxCacheInvalidator invalidator = new OutboxCacheInvalidator(template, fallback, properties);

        invalidator.invalidate("user", Arrays.asList("k1", "k2", "k3"));

        assertEquals(0, template.calls);
        assertEquals(1, fallback.calls);
        assertEquals("user", fallback.namespace);
        assertEquals(3, fallback.keys.size());
    }

    @Test
    void emptyKeysShouldBeIgnored() {
        OutpostProperties properties = new OutpostProperties();
        RecordingTemplate template = new RecordingTemplate();
        RecordingInvalidator fallback = new RecordingInvalidator();
        OutboxCacheInvalidator invalidator = new OutboxCacheInvalidator(template, fallback, properties);

        invalidator.invalidate("order", null);
        invalidator.invalidate("order", Collections.<String>emptyList());

        assertEquals(0, template.calls);
        assertEquals(0, fallback.calls);
    }

    private static final class RecordingTemplate implements OutpostTemplate {

        private int calls;
        private String namespace;
        private List<String> keys;

        @Override
        public <T> T executeInTransaction(Supplier<T> businessAction) {
            return businessAction.get();
        }

        @Override
        public void registerMessage(String bizType, String bizKey, String payload) {
        }

        @Override
        public void registerDelayedMessage(String bizType, String bizKey, String payload, long delayMills) {
        }

        @Override
        public void registerCacheInvalidate(String namespace, List<String> cacheKeys) {
            this.calls++;
            this.namespace = namespace;
            this.keys = new ArrayList<String>(cacheKeys);
        }
    }

    private static final class RecordingInvalidator implements CacheInvalidator {

        private int calls;
        private String namespace;
        private List<String> keys;

        @Override
        public void invalidate(String namespace, List<String> keys) {
            this.calls++;
            this.namespace = namespace;
            this.keys = new ArrayList<String>(keys);
        }
    }
}
