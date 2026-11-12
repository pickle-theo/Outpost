package com.outpost.core.delivery;

import com.outpost.core.message.DeadReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeadMessageNotifierTest {

    @Test
    void shouldContinueWithNextListenerWhenOneThrows() {
        final AtomicInteger secondCalls = new AtomicInteger();
        DeadMessageListener failing = new DeadMessageListener() {
            @Override
            public void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
                throw new IllegalStateException("boom");
            }
        };
        DeadMessageListener counting = new DeadMessageListener() {
            @Override
            public void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
                secondCalls.incrementAndGet();
            }
        };

        DeadMessageNotifier notifier = new DeadMessageNotifier(Arrays.asList(failing, counting));

        assertDoesNotThrow(() -> notifier.notifyDead(message(), DeadReason.RETRY_EXHAUSTED,
                new RuntimeException("cause")));
        assertEquals(1, secondCalls.get());
    }

    @Test
    void shouldNotifyAllListeners() {
        final AtomicInteger calls = new AtomicInteger();
        DeadMessageListener listener = new DeadMessageListener() {
            @Override
            public void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
                calls.incrementAndGet();
            }
        };

        new DeadMessageNotifier(Collections.singletonList(listener))
                .notifyDead(message(), DeadReason.UNRECOVERABLE, null);

        assertEquals(1, calls.get());
    }

    @Test
    void shouldTolerateEmptyListenerList() {
        assertDoesNotThrow(() -> new DeadMessageNotifier(Collections.<DeadMessageListener>emptyList())
                .notifyDead(message(), DeadReason.RETRY_EXHAUSTED, null));
        assertDoesNotThrow(() -> new DeadMessageNotifier(null)
                .notifyDead(message(), DeadReason.UNRECOVERABLE, null));
    }

    private static OutboxDeliveryMessage message() {
        return new OutboxDeliveryMessage(1L, "cache_delete", "ns:key", "key",
                2, "trace-1", LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
