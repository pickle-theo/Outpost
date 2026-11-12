package com.outpost.starter.cache;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为单次 Redis 操作提供时间上界：任务提交到类级共享的守护线程池，调用线程最多等待
 * {@code timeoutMillis}，超时后取消任务并抛出 {@link RedisOperationTimeoutException}。
 *
 * <p>共享线程池可避免每条消息创建线程；队列满时由 {@code CallerRunsPolicy} 回压，
 * 保证任务最终执行而不是被丢弃。</p>
 */
public class TimeoutRedisExecutor {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 200;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private static final ThreadPoolExecutor SHARED_EXECUTOR = createExecutor();

    private final long timeoutMillis;

    public TimeoutRedisExecutor(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis 必须大于 0: " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
    }

    public <T> T execute(Callable<T> action, String operation) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null");
        }
        Future<T> future = SHARED_EXECUTOR.submit(action);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RedisOperationTimeoutException(
                    "Redis 操作超时: " + operation + ", timeout=" + timeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Redis 操作被中断: " + operation, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException("Redis 操作失败: " + operation, cause);
        }
    }

    private static ThreadPoolExecutor createExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "outpost-redis-timeout-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
