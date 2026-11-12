package com.outpost.starter.aop;

import com.outpost.core.annotation.OutboxCache;
import com.outpost.core.annotation.OutboxMessage;
import com.outpost.core.produce.OutpostTemplate;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.reflect.SourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxMessageAspectTest {

    private RecordingTemplate template;
    private OutboxMessageAspect aspect;
    private OrderService service;

    @BeforeEach
    void setUp() {
        template = new RecordingTemplate();
        aspect = new OutboxMessageAspect(template, new SpelValueResolver());
        service = new OrderService();
    }

    @Test
    void shouldRegisterMessageAfterSuccessfulReturn() throws Exception {
        Method method = OrderService.class.getMethod("create", String.class, String.class);

        aspect.afterReturningOutboxMessage(joinPoint(method, "o-1", "{\"id\":1}"), "ok");

        assertEquals(1, template.messageCalls);
        assertEquals(0, template.delayedCalls);
        assertEquals("order.created", template.bizType);
        assertEquals("o-1", template.bizKey);
        assertEquals("{\"id\":1}", template.payload);
    }

    @Test
    void shouldRegisterNullPayloadWhenPayloadExpressionEmpty() throws Exception {
        Method method = OrderService.class.getMethod("createWithoutPayload", String.class);

        aspect.afterReturningOutboxMessage(joinPoint(method, "o-2"), "ok");

        assertEquals(1, template.messageCalls);
        assertNull(template.payload);
    }

    @Test
    void shouldRegisterDelayedMessageWhenDelayConfigured() throws Exception {
        Method method = OrderService.class.getMethod("createDelayed", String.class);

        aspect.afterReturningOutboxMessage(joinPoint(method, "o-3"), "ok");

        assertEquals(0, template.messageCalls);
        assertEquals(1, template.delayedCalls);
        assertEquals(1000L, template.delayMills);
    }

    @Test
    void shouldRegisterSingleCacheKey() throws Exception {
        Method method = OrderService.class.getMethod("evictOne", String.class);

        aspect.afterReturningOutboxCache(joinPoint(method, "user:1"));

        assertEquals(1, template.cacheCalls);
        assertEquals("order", template.namespace);
        assertEquals(Collections.singletonList("user:1"), template.cacheKeys);
    }

    @Test
    void shouldExpandMultipleCacheKeys() throws Exception {
        Method method = OrderService.class.getMethod("evictMany", List.class);

        aspect.afterReturningOutboxCache(joinPoint(method,
                (Object) Arrays.asList("user:1", "user:2")));

        assertEquals(1, template.cacheCalls);
        assertEquals(Arrays.asList("user:1", "user:2"), template.cacheKeys);
    }

    @Test
    void shouldSkipEmptyCacheKeys() throws Exception {
        Method method = OrderService.class.getMethod("evictMany", List.class);

        aspect.afterReturningOutboxCache(joinPoint(method, (Object) Collections.emptyList()));

        assertEquals(0, template.cacheCalls);
    }

    @Test
    void shouldSkipNullSingleCacheKey() throws Exception {
        Method method = OrderService.class.getMethod("evictOne", String.class);

        aspect.afterReturningOutboxCache(joinPoint(method, new Object[]{null}));

        assertEquals(0, template.cacheCalls);
    }

    @Test
    void shouldRejectKeyAndKeysConfiguredTogether() throws Exception {
        Method method = OrderService.class.getMethod("conflict", String.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> aspect.afterReturningOutboxCache(joinPoint(method, "user:1")));

        assertTrue(error.getMessage().contains("key"));
    }

    @Test
    void shouldRejectNeitherKeyNorKeysConfigured() throws Exception {
        Method method = OrderService.class.getMethod("missingKey", String.class);

        assertThrows(IllegalArgumentException.class,
                () -> aspect.afterReturningOutboxCache(joinPoint(method, "user:1")));
    }

    private StubJoinPoint joinPoint(Method method, Object... args) {
        return new StubJoinPoint(method, service, args);
    }

    /**
     * 测试用业务类，方法上直接标注注解。
     */
    public static class OrderService {

        @OutboxMessage(bizType = "order.created", bizKey = "#p0", payload = "#p1")
        public String create(String orderId, String body) {
            return "ok";
        }

        @OutboxMessage(bizType = "order.created", bizKey = "#p0")
        public String createWithoutPayload(String orderId) {
            return "ok";
        }

        @OutboxMessage(bizType = "order.delayed", bizKey = "#p0", delayMills = 1000L)
        public String createDelayed(String orderId) {
            return "ok";
        }

        @OutboxCache(namespace = "order", key = "#p0")
        public void evictOne(String key) {
            // no-op
        }

        @OutboxCache(namespace = "order", keys = "#p0")
        public void evictMany(List<String> keys) {
            // no-op
        }

        @OutboxCache(namespace = "order", key = "#p0", keys = "#p0")
        public void conflict(String key) {
            // no-op
        }

        @OutboxCache(namespace = "order")
        public void missingKey(String key) {
            // no-op
        }
    }

    private static final class RecordingTemplate implements OutpostTemplate {

        private int messageCalls;
        private int delayedCalls;
        private int cacheCalls;
        private String bizType;
        private String bizKey;
        private String payload;
        private long delayMills;
        private String namespace;
        private List<String> cacheKeys;

        @Override
        public <T> T executeInTransaction(Supplier<T> businessAction) {
            return businessAction.get();
        }

        @Override
        public void registerMessage(String bizType, String bizKey, String payload) {
            messageCalls++;
            this.bizType = bizType;
            this.bizKey = bizKey;
            this.payload = payload;
        }

        @Override
        public void registerDelayedMessage(String bizType, String bizKey, String payload, long delayMills) {
            delayedCalls++;
            this.bizType = bizType;
            this.bizKey = bizKey;
            this.payload = payload;
            this.delayMills = delayMills;
        }

        @Override
        public void registerCacheInvalidate(String namespace, List<String> cacheKeys) {
            cacheCalls++;
            this.namespace = namespace;
            this.cacheKeys = new ArrayList<String>(cacheKeys);
        }
    }

    private static final class StubJoinPoint implements JoinPoint {

        private final Method method;
        private final Object target;
        private final Object[] args;

        StubJoinPoint(Method method, Object target, Object[] args) {
            this.method = method;
            this.target = target;
            this.args = args;
        }

        @Override
        public String toShortString() {
            return method.getName();
        }

        @Override
        public String toLongString() {
            return method.toString();
        }

        @Override
        public Object getThis() {
            return target;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return new StubMethodSignature(method);
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return METHOD_EXECUTION;
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }
    }

    private static final class StubMethodSignature implements MethodSignature {

        private final Method method;

        StubMethodSignature(Method method) {
            this.method = method;
        }

        @Override
        public Class<?> getReturnType() {
            return method.getReturnType();
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public String[] getParameterNames() {
            return new String[0];
        }

        @Override
        public Class<?>[] getParameterTypes() {
            return method.getParameterTypes();
        }

        @Override
        public Class<?>[] getExceptionTypes() {
            return method.getExceptionTypes();
        }

        @Override
        public String getName() {
            return method.getName();
        }

        @Override
        public String toShortString() {
            return method.getName();
        }

        @Override
        public String toLongString() {
            return method.toString();
        }

        @Override
        public Class<?> getDeclaringType() {
            return method.getDeclaringClass();
        }

        @Override
        public String getDeclaringTypeName() {
            return method.getDeclaringClass().getName();
        }

        @Override
        public int getModifiers() {
            return method.getModifiers();
        }
    }
}
