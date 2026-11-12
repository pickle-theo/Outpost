package com.outpost.starter.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TableNameResolverTest {

    @Test
    void shouldUseDefaultPrefix() {
        assertEquals("outpost_", new TableNameResolver().prefix());
        assertEquals("outpost_message", new TableNameResolver().messageTable());
    }

    @Test
    void shouldAcceptLegalPrefix() {
        assertEquals("custom_1message", new TableNameResolver("custom_1").messageTable());
    }

    @Test
    void shouldAllowEmptyPrefix() {
        assertEquals("", new TableNameResolver("").prefix());
        assertEquals("message", new TableNameResolver("").messageTable());
    }

    @Test
    void shouldRejectNullPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver(null));
    }

    @Test
    void shouldRejectBlankPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver(" "));
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver("out post"));
    }

    @Test
    void shouldRejectSpecialCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver("a;b"));
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver("a`b"));
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver("a-b"));
    }

    @Test
    void shouldRejectTooLongPrefix() {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            prefix.append('a');
        }
        assertThrows(IllegalArgumentException.class, () -> new TableNameResolver(prefix.toString()));
    }

    @Test
    void shouldAcceptMaxLengthPrefix() {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            prefix.append('a');
        }
        assertEquals(prefix + "message", new TableNameResolver(prefix.toString()).messageTable());
    }
}
