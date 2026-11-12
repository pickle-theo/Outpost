package com.outpost.starter.aop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelValueResolverTest {

    private final SpelValueResolver resolver = new SpelValueResolver();
    private final Fixture fixture = new Fixture();

    @Test
    void shouldResolveStringLiteral() throws Exception {
        Method method = Fixture.class.getMethod("greet", String.class);

        assertEquals("hello", resolver.resolveString("'hello'", method,
                new Object[]{"bob"}, fixture));
    }

    @Test
    void shouldResolveByPositionAndParameterName() throws Exception {
        Method method = Fixture.class.getMethod("greet", String.class);

        assertEquals("bob", resolver.resolveString("#p0", method, new Object[]{"bob"}, fixture));
        assertEquals("bob", resolver.resolveString("#name", method, new Object[]{"bob"}, fixture));
        assertEquals("BOB", resolver.resolveString("#p0.toUpperCase()", method,
                new Object[]{"bob"}, fixture));
    }

    @Test
    void shouldReturnNullWhenResultNull() throws Exception {
        Method method = Fixture.class.getMethod("greet", String.class);

        assertNull(resolver.resolveString("#p0", method, new Object[]{null}, fixture));
    }

    @Test
    void shouldRejectNonStringResult() throws Exception {
        Method method = Fixture.class.getMethod("any", Object.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveString("#p0", method, new Object[]{42}, fixture));

        assertTrue(error.getMessage().contains("java.lang.Integer"));
    }

    @Test
    void shouldResolveStringList() throws Exception {
        Method method = Fixture.class.getMethod("keys", List.class);

        List<String> keys = resolver.resolveStringList("#p0", method,
                new Object[]{Arrays.asList("a", "b")}, fixture);

        assertEquals(Arrays.asList("a", "b"), keys);
    }

    @Test
    void shouldReturnEmptyListWhenResultNull() throws Exception {
        Method method = Fixture.class.getMethod("keys", List.class);

        List<String> keys = resolver.resolveStringList("#p0", method,
                new Object[]{null}, fixture);

        assertTrue(keys.isEmpty());
    }

    @Test
    void shouldRejectNonListResult() throws Exception {
        Method method = Fixture.class.getMethod("keys", List.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveStringList("#p0", method, new Object[]{"abc"}, fixture));

        assertTrue(error.getMessage().contains("java.util.List"));
        assertTrue(error.getMessage().contains("java.lang.String"));
    }

    @Test
    void shouldRejectListWithNonStringElement() throws Exception {
        Method method = Fixture.class.getMethod("keys", List.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveStringList("#p0", method,
                        new Object[]{Arrays.asList("a", 5)}, fixture));

        assertTrue(error.getMessage().contains("java.lang.Integer"));
    }

    @Test
    void shouldRejectEmptyExpression() throws Exception {
        Method method = Fixture.class.getMethod("greet", String.class);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveString(" ", method, new Object[]{"bob"}, fixture));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveStringList(null, method, new Object[]{Collections.emptyList()}, fixture));
    }

    public static class Fixture {

        public String greet(String name) {
            return "hi " + name;
        }

        public String any(Object value) {
            return "any";
        }

        public List<String> keys(List<String> keys) {
            return keys;
        }
    }
}
