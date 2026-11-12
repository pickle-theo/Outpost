package com.outpost.core.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultBackoffPolicyTest {

    private final BackoffPolicy policy =
            new DefaultBackoffPolicy(new long[]{1L, 3L, 10L, 30L, 60L, 300L});

    @Test
    void shouldFollowSteps() {
        assertEquals(1L, policy.nextDelaySeconds(1));
        assertEquals(3L, policy.nextDelaySeconds(2));
        assertEquals(10L, policy.nextDelaySeconds(3));
        assertEquals(30L, policy.nextDelaySeconds(4));
        assertEquals(60L, policy.nextDelaySeconds(5));
        assertEquals(300L, policy.nextDelaySeconds(6));
    }

    @Test
    void shouldUseLastStepWhenRetryCountExceedsSteps() {
        assertEquals(300L, policy.nextDelaySeconds(7));
        assertEquals(300L, policy.nextDelaySeconds(10));
        assertEquals(300L, policy.nextDelaySeconds(Integer.MAX_VALUE));
    }

    @Test
    void shouldRejectNonPositiveRetryCount() {
        assertThrows(IllegalArgumentException.class, () -> policy.nextDelaySeconds(0));
        assertThrows(IllegalArgumentException.class, () -> policy.nextDelaySeconds(-1));
    }

    @Test
    void shouldRejectInvalidSteps() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultBackoffPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> new DefaultBackoffPolicy(new long[0]));
        assertThrows(IllegalArgumentException.class, () -> new DefaultBackoffPolicy(new long[]{1L, 0L}));
        assertThrows(IllegalArgumentException.class, () -> new DefaultBackoffPolicy(new long[]{-1L}));
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultBackoffPolicy(new long[]{1L, 3L, 3L}));
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultBackoffPolicy(new long[]{5L, 3L}));
    }

    @Test
    void shouldDefensivelyCopySteps() {
        long[] steps = new long[]{1L, 3L};
        BackoffPolicy copied = new DefaultBackoffPolicy(steps);
        steps[0] = 100L;

        assertEquals(1L, copied.nextDelaySeconds(1));
    }
}
