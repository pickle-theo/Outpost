package com.outpost.core.retry;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热更的退避策略包装：运维修改 {@code retry.backoff-steps} 后替换内部委托即可生效。
 */
public final class DynamicBackoffPolicy implements BackoffPolicy {

    private final AtomicReference<BackoffPolicy> delegate;

    public DynamicBackoffPolicy(BackoffPolicy initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial 不能为 null");
        }
        this.delegate = new AtomicReference<BackoffPolicy>(initial);
    }

    public void setDelegate(BackoffPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为 null");
        }
        delegate.set(policy);
    }

    public BackoffPolicy getDelegate() {
        return delegate.get();
    }

    @Override
    public long nextDelaySeconds(int retryCount) {
        return delegate.get().nextDelaySeconds(retryCount);
    }
}
