package com.outpost.core.produce;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.monitor.NoopOutpostMetrics;
import com.outpost.core.monitor.OutpostMetrics;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事务内消息聚合器：把同一事务内注册的消息聚合起来，在事务提交前批量写入本地消息表。
 *
 * <h3>事务上下文</h3>
 * <p>注册前必须同时满足：事务同步已激活、存在真实事务、Outpost 使用的 {@code DataSource}
 * 已绑定到当前线程。任一条件不满足都会抛出 {@link IllegalStateException}，
 * 「无事务上下文」的告警日志通过 {@link AtomicBoolean} 保证全局只输出一次，但每次调用都抛异常。</p>
 *
 * <h3>作用域隔离（REQUIRES_NEW）</h3>
 * <p>{@link ThreadLocal} 中保存的是「作用域栈」，每个作用域自身实现
 * {@link TransactionSynchronization} 并注册到当前事务。注册消息时，只有当栈顶作用域实例
 * 仍出现在 {@link TransactionSynchronizationManager#getSynchronizations()} 中时才复用，
 * 否则说明进入了新的独立事务（{@code REQUIRES_NEW} 会挂起外层同步并重新初始化），
 * 需要新建作用域。由此保证：</p>
 * <ul>
 *     <li>外层事务注册的消息只在外层 {@code beforeCommit} 落库；</li>
 *     <li>{@code REQUIRES_NEW} 内部注册的消息只在内层提交时落库；</li>
 *     <li>内层回滚不影响外层已注册消息，外层回滚也不影响已提交的内层消息；</li>
 *     <li>每个作用域在 {@code afterCompletion} 中无条件从栈中移除，异常路径同样清理，
 *         避免线程复用导致消息串到下一个请求。</li>
 * </ul>
 *
 * <h3>线程安全边界</h3>
 * <p>本类实例本身无状态，可安全复用；聚合状态保存在 {@link ThreadLocal} 中，
 * 因此只保证「同一事务同步回调线程」内的可见性。禁止跨线程注册消息，
 * 也禁止在事务提交过程中异步修改聚合结果。</p>
 */
public final class TransactionMessageCollector {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionMessageCollector.class);

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private static final String TRACE_ID_KEY = "traceId";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 当前线程的作用域栈；仅在该线程的事务同步回调中访问。 */
    private static final ThreadLocal<Deque<MessageScope>> SCOPES = new ThreadLocal<Deque<MessageScope>>() {
        @Override
        protected Deque<MessageScope> initialValue() {
            return new ArrayDeque<MessageScope>();
        }
    };

    private final OutpostMessageRepository repository;
    private final DataSource dataSource;
    private final OutpostProperties properties;
    private final OutpostMetrics metrics;

    public TransactionMessageCollector(OutpostMessageRepository repository, DataSource dataSource,
                                       OutpostProperties properties) {
        this(repository, dataSource, properties, NoopOutpostMetrics.INSTANCE);
    }

    public TransactionMessageCollector(OutpostMessageRepository repository, DataSource dataSource,
                                       OutpostProperties properties, OutpostMetrics metrics) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics 不能为 null");
        }
        this.repository = repository;
        this.dataSource = dataSource;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 注册一条消息到当前事务作用域。
     *
     * @throws IllegalStateException    当前线程不存在与 Outpost 数据源绑定的事务
     * @throws IllegalArgumentException payload 超过配置上限
     */
    public void register(MessageRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration 不能为 null");
        }
        checkTransactionContext();
        currentScope().add(registration);
    }

    private void checkTransactionContext() {
        boolean inTransaction = TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource);
        if (inTransaction) {
            return;
        }
        if (WARNED.compareAndSet(false, true)) {
            LOG.warn("Outpost 未检测到与消息表数据源绑定的事务上下文，消息注册将失败。"
                    + "排查提示：1) 调用方是否添加了 @Transactional；2) 消息表与业务表是否使用同一个 "
                    + "DataSource bean 实例；3) 是否在 Bean 初始化阶段调用了注册方法。");
        }
        throw new IllegalStateException("当前方法无事务上下文，请添加 @Transactional 或确保在事务内调用；"
                + "并确认消息表与业务表共用同一个 DataSource bean 实例");
    }

    private MessageScope currentScope() {
        Deque<MessageScope> scopes = SCOPES.get();
        MessageScope top = scopes.peekLast();
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (top != null && synchronizations.contains(top)) {
            return top;
        }
        MessageScope scope = new MessageScope();
        TransactionSynchronizationManager.registerSynchronization(scope);
        scopes.addLast(scope);
        return scope;
    }

    private void flushRegistrations(MessageScope scope) {
        if (scope.registrations.isEmpty()) {
            return;
        }
        List<MessageRegistration> registrations = new ArrayList<MessageRegistration>(scope.registrations);
        int total = registrations.size();
        int threshold = properties.getBatchRegisterThreshold();
        if (threshold > 0 && total > threshold) {
            LOG.warn("单个事务注册消息数 {} 超过阈值 {}，将分批插入，请检查是否存在异常的批量写入",
                    total, threshold);
        }
        List<OutboxMessageRecord> records = new ArrayList<OutboxMessageRecord>(total);
        for (MessageRegistration registration : registrations) {
            records.add(toRecord(registration));
        }
        int batchSize = threshold > 0 ? threshold : total;
        for (int from = 0; from < total; from += batchSize) {
            int to = Math.min(from + batchSize, total);
            repository.insertBatch(records.subList(from, to));
        }
        scope.registrations.clear();
        metrics.onMessageProduced(total);
    }

    private OutboxMessageRecord toRecord(MessageRegistration registration) {
        validatePayload(registration.getPayload());
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setBizType(registration.getBizType());
        record.setBizKey(registration.getBizKey());
        record.setPayload(registration.getPayload());
        record.setMessageType(registration.getMessageType());
        record.setStatus(MessageStatus.PENDING.code());
        record.setRetryCount(0);
        record.setMaxRetry(registration.getMaxRetry());
        record.setDeliverAttempt(0);
        record.setNextRetryTime(LocalDateTime.now()
                .plusNanos(registration.getDelayMills() * NANOS_PER_MILLI));
        record.setVersion(0);
        record.setTraceId(MDC.get(TRACE_ID_KEY));
        return record;
    }

    private void validatePayload(String payload) {
        if (payload == null) {
            return;
        }
        int maxPayloadBytes = properties.getMaxPayloadBytes();
        int actualBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("payload 大小 " + actualBytes + " 字节超过上限 "
                    + maxPayloadBytes + " 字节，请拆分或压缩消息体，SDK 不会截断");
        }
    }

    /**
     * 单个事务作用域，同时作为注册到当前事务的标记 synchronization。
     */
    private final class MessageScope implements TransactionSynchronization {

        private final List<MessageRegistration> registrations = new ArrayList<MessageRegistration>();

        void add(MessageRegistration registration) {
            registrations.add(registration);
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            flushRegistrations(this);
        }

        @Override
        public void afterCompletion(int status) {
            Deque<MessageScope> scopes = SCOPES.get();
            scopes.remove(this);
            if (scopes.isEmpty()) {
                SCOPES.remove();
            }
        }
    }
}
