package com.outpost.core.admin;

/**
 * 消息表统计快照，统计口径默认排除辅助消息。
 */
public final class OutpostStats {

    private long total;
    private long pending;
    private long processing;
    private long success;
    private long failed;
    private long dead;
    private long deadRetryExhausted;
    private long deadUnrecoverable;
    private long redelivery;

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPending() {
        return pending;
    }

    public void setPending(long pending) {
        this.pending = pending;
    }

    public long getProcessing() {
        return processing;
    }

    public void setProcessing(long processing) {
        this.processing = processing;
    }

    public long getSuccess() {
        return success;
    }

    public void setSuccess(long success) {
        this.success = success;
    }

    public long getFailed() {
        return failed;
    }

    public void setFailed(long failed) {
        this.failed = failed;
    }

    public long getDead() {
        return dead;
    }

    public void setDead(long dead) {
        this.dead = dead;
    }

    public long getDeadRetryExhausted() {
        return deadRetryExhausted;
    }

    public void setDeadRetryExhausted(long deadRetryExhausted) {
        this.deadRetryExhausted = deadRetryExhausted;
    }

    public long getDeadUnrecoverable() {
        return deadUnrecoverable;
    }

    public void setDeadUnrecoverable(long deadUnrecoverable) {
        this.deadUnrecoverable = deadUnrecoverable;
    }

    public long getRedelivery() {
        return redelivery;
    }

    public void setRedelivery(long redelivery) {
        this.redelivery = redelivery;
    }

    /**
     * 重复投递占比：{@code deliver_attempt > retry_count + 1} 的行数 / 总行数。
     */
    public double getRedeliveryRatio() {
        return total <= 0L ? 0D : (double) redelivery / total;
    }
}
