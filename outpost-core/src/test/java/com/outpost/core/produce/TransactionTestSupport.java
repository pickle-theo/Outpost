package com.outpost.core.produce;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 事务同步状态的内存模拟工具，仅供 core 模块单元测试使用。
 */
final class TransactionTestSupport {

    private TransactionTestSupport() {
    }

    static DataSource newDataSource() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == long.class) {
                    return 0L;
                }
                return null;
            }
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, handler);
    }

    static void begin(DataSource dataSource) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new Object());
    }

    static void triggerBeforeCommit() {
        for (TransactionSynchronization synchronization : snapshot()) {
            synchronization.beforeCommit(false);
        }
    }

    static void complete(DataSource dataSource, int status) {
        for (TransactionSynchronization synchronization : snapshot()) {
            synchronization.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
    }

    static List<TransactionSynchronization> snapshot() {
        return new ArrayList<TransactionSynchronization>(
                TransactionSynchronizationManager.getSynchronizations());
    }

    static void reset() {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 记录 insertBatch 调用的内存仓储桩。
     */
    static final class RecordingRepository implements OutpostMessageRepository {

        final List<List<OutboxMessageRecord>> batches = new ArrayList<List<OutboxMessageRecord>>();

        List<OutboxMessageRecord> allRecords() {
            List<OutboxMessageRecord> records = new ArrayList<OutboxMessageRecord>();
            for (List<OutboxMessageRecord> batch : batches) {
                records.addAll(batch);
            }
            return records;
        }

        @Override
        public int insertBatch(List<OutboxMessageRecord> records) {
            batches.add(new ArrayList<OutboxMessageRecord>(records));
            return records.size();
        }

        @Override
        public List<OutboxMessageRecord> selectDue(int limit) {
            return Collections.emptyList();
        }

        @Override
        public int claimBatch(List<Long> ids) {
            return 0;
        }

        @Override
        public boolean claimOne(Long id) {
            return false;
        }

        @Override
        public List<OutboxMessageRecord> selectByIds(List<Long> ids) {
            return Collections.emptyList();
        }

        @Override
        public int markSuccessBatch(List<Long> ids) {
            return 0;
        }

        @Override
        public int markFailed(Long id, int newRetryCount, long backoffSeconds, String errorMsg) {
            return 0;
        }

        @Override
        public int markDead(Long id, int newRetryCount, DeadReason reason, String errorMsg) {
            return 0;
        }

        @Override
        public int resetTimeoutProcessing(int timeoutSeconds) {
            return 0;
        }

        @Override
        public long countDead() {
            return 0L;
        }

        @Override
        public long countAll() {
            return 0L;
        }

        @Override
        public long countRedelivery() {
            return 0L;
        }
    }
}
