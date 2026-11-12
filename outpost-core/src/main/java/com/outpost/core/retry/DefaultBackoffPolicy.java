package com.outpost.core.retry;

/**
 * 阶梯退避策略：超过阶梯长度后按最后一级重试。
 */
public final class DefaultBackoffPolicy implements BackoffPolicy {

    private final long[] steps;

    public DefaultBackoffPolicy(long[] steps) {
        if (steps == null || steps.length == 0) {
            throw new IllegalArgumentException("退避阶梯不能为空");
        }
        this.steps = steps.clone();
        for (int i = 0; i < this.steps.length; i++) {
            if (this.steps[i] <= 0L) {
                throw new IllegalArgumentException("退避阶梯第 " + i + " 项必须大于 0，实际值: " + this.steps[i]);
            }
            if (i > 0 && this.steps[i] <= this.steps[i - 1]) {
                throw new IllegalArgumentException("退避阶梯必须严格递增，index " + (i - 1) + "="
                        + this.steps[i - 1] + "，index " + i + "=" + this.steps[i]);
            }
        }
    }

    @Override
    public long nextDelaySeconds(int retryCount) {
        if (retryCount <= 0) {
            throw new IllegalArgumentException("retryCount 必须从 1 开始，实际值: " + retryCount);
        }
        int index = Math.min(retryCount, steps.length) - 1;
        return steps[index];
    }
}
