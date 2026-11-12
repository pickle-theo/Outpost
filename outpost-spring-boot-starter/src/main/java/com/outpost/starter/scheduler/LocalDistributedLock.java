package com.outpost.starter.scheduler;

import com.outpost.core.schedule.DistributedLock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 JVM 内的分布式锁替代实现，供纯通用 Outbox 场景（无需 Redis）使用。
 *
 * <p>每个 key 对应一个 CAS 标志：同一时刻只允许一个持有者，语义与
 * {@link RedisDistributedLock} 一致（{@code unlock} 不校验持有线程）。
 * 进程内锁不会因网络超时残留，因此忽略 {@code ttlMillis}。</p>
 *
 * <p><b>为什么不用 {@code ReentrantLock}：</b>调度器与清理任务可能在同一个线程上
 * 多次调用 {@code tryLock}，可重入锁会让「锁未释放」的第二次调用错误地返回成功。</p>
 *
 * <p><b>限制：</b>只在单实例部署有效。多副本必须使用 {@link RedisDistributedLock}，
 * 否则各实例会重复扫表；逐条 CAS 可保证单条消息只被一个实例抢占，
 * 但超时重置仍可能造成重复投递，handler 幂等是硬要求。</p>
 */
public class LocalDistributedLock implements DistributedLock {

    private final ConcurrentHashMap<String, AtomicBoolean> held =
            new ConcurrentHashMap<String, AtomicBoolean>();

    @Override
    public boolean tryLock(String key, long ttlMillis) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("lockKey 不能为空");
        }
        AtomicBoolean flag = held.get(key);
        if (flag == null) {
            AtomicBoolean created = new AtomicBoolean(false);
            AtomicBoolean existing = held.putIfAbsent(key, created);
            flag = existing == null ? created : existing;
        }
        return flag.compareAndSet(false, true);
    }

    @Override
    public void unlock(String key) {
        if (key == null) {
            return;
        }
        AtomicBoolean flag = held.get(key);
        if (flag != null) {
            flag.set(false);
        }
    }
}
