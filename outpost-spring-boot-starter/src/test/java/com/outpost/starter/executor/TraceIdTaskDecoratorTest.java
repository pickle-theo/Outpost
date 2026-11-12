package com.outpost.starter.executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdTaskDecoratorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldPassTraceIdToWorkerAndRestoreWorkerMdc() {
        MDC.put("traceId", "submit-trace");
        final List<String> seen = new ArrayList<String>();
        final TraceIdTaskDecorator decorator = new TraceIdTaskDecorator();
        final Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                seen.add(MDC.get("traceId"));
            }
        });

        // decorate 不应修改调用线程的 MDC
        assertEquals("submit-trace", MDC.get("traceId"));

        MDC.put("traceId", "worker-trace");
        try {
            decorated.run();
            assertEquals("submit-trace", seen.get(0));
            assertEquals("worker-trace", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldClearTraceIdWhenSubmitterHasNone() {
        final List<String> seen = new ArrayList<String>();
        Runnable decorated = new TraceIdTaskDecorator().decorate(new Runnable() {
            @Override
            public void run() {
                seen.add(MDC.get("traceId"));
            }
        });

        MDC.put("traceId", "worker-trace");
        try {
            decorated.run();
            assertNull(seen.get(0));
            assertEquals("worker-trace", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }
}
