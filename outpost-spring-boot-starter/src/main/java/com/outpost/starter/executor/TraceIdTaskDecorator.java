package com.outpost.starter.executor;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交线程 MDC 中的 traceId 传递到线程池执行线程，执行结束后恢复执行线程原值。
 */
public class TraceIdTaskDecorator implements TaskDecorator {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    public Runnable decorate(final Runnable runnable) {
        final String traceId = MDC.get(TRACE_ID_KEY);
        return new Runnable() {
            @Override
            public void run() {
                String previous = MDC.get(TRACE_ID_KEY);
                try {
                    if (traceId == null) {
                        MDC.remove(TRACE_ID_KEY);
                    } else {
                        MDC.put(TRACE_ID_KEY, traceId);
                    }
                    runnable.run();
                } finally {
                    if (previous == null) {
                        MDC.remove(TRACE_ID_KEY);
                    } else {
                        MDC.put(TRACE_ID_KEY, previous);
                    }
                }
            }
        };
    }
}
