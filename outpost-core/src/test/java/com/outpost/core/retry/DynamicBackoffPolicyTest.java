package com.outpost.core.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicBackoffPolicyTest {

    @Test
    void delegatesToCurrentPolicy() {
        DynamicBackoffPolicy policy = new DynamicBackoffPolicy(
                new DefaultBackoffPolicy(new long[]{1L, 3L}));
        assertEquals(1L, policy.nextDelaySeconds(1));

        policy.setDelegate(new DefaultBackoffPolicy(new long[]{5L, 20L}));
        assertEquals(5L, policy.nextDelaySeconds(1));
        assertEquals(20L, policy.nextDelaySeconds(2));
    }

    @Test
    void rejectsNullDelegate() {
        DynamicBackoffPolicy policy = new DynamicBackoffPolicy(
                new DefaultBackoffPolicy(new long[]{1L}));
        assertThrows(IllegalArgumentException.class, () -> policy.setDelegate(null));
        assertThrows(IllegalArgumentException.class, () -> new DynamicBackoffPolicy(null));
    }
}
