package com.outpost.core.support;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.message.OutpostMessageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存桩仓储：记录调用轨迹，便于内核单测断言状态回写分支。
 */
public class InMemoryOutpostMessageRepository implements OutpostMessageRepository {

    public final List<List<Long>> successBatches = new CopyOnWriteArrayList<List<Long>>();
    public final List<FailedCall> failedCalls = new CopyOnWriteArrayList<FailedCall>();
    public final List<DeadCall> deadCalls = new CopyOnWriteArrayList<DeadCall>();
    public final List<List<Long>> claimBatchCalls = new CopyOnWriteArrayList<List<Long>>();
    public final List<Long> claimOneCalls = new CopyOnWriteArrayList<Long>();
    public final List<Integer> resetTimeoutCalls = new CopyOnWriteArrayList<Integer>();

    public volatile List<OutboxMessageRecord> due = Collections.emptyList();
    public volatile Map<Long, OutboxMessageRecord> byId = new LinkedHashMap<Long, OutboxMessageRecord>();
    public volatile int claimBatchAffected = -1;
    public volatile Set<Long> claimableIds = new LinkedHashSet<Long>();
    public volatile RuntimeException writeError;
    public volatile RuntimeException selectError;

    @Override
    public int insertBatch(List<OutboxMessageRecord> records) {
        return records == null ? 0 : records.size();
    }

    @Override
    public List<OutboxMessageRecord> selectDue(int limit) {
        if (selectError != null) {
            throw selectError;
        }
        return due;
    }

    @Override
    public int claimBatch(List<Long> ids) {
        claimBatchCalls.add(new ArrayList<Long>(ids));
        return claimBatchAffected >= 0 ? claimBatchAffected : ids.size();
    }

    @Override
    public boolean claimOne(Long id) {
        claimOneCalls.add(id);
        return claimableIds.contains(id);
    }

    @Override
    public List<OutboxMessageRecord> selectByIds(List<Long> ids) {
        List<OutboxMessageRecord> result = new ArrayList<OutboxMessageRecord>();
        for (Long id : ids) {
            OutboxMessageRecord record = byId.get(id);
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    @Override
    public int markSuccessBatch(List<Long> ids) {
        checkWrite();
        successBatches.add(new ArrayList<Long>(ids));
        return ids.size();
    }

    @Override
    public int markFailed(Long id, int newRetryCount, long backoffSeconds, String errorMsg) {
        checkWrite();
        failedCalls.add(new FailedCall(id, newRetryCount, backoffSeconds, errorMsg));
        return 1;
    }

    @Override
    public int markDead(Long id, int newRetryCount, DeadReason reason, String errorMsg) {
        checkWrite();
        deadCalls.add(new DeadCall(id, newRetryCount, reason, errorMsg));
        return 1;
    }

    @Override
    public int resetTimeoutProcessing(int timeoutSeconds) {
        resetTimeoutCalls.add(timeoutSeconds);
        return 0;
    }

    @Override
    public long countDead() {
        return deadCalls.size();
    }

    @Override
    public long countAll() {
        return 0L;
    }

    @Override
    public long countRedelivery() {
        return 0L;
    }

    private void checkWrite() {
        if (writeError != null) {
            throw writeError;
        }
    }

    public static final class FailedCall {

        public final Long id;
        public final int newRetryCount;
        public final long backoffSeconds;
        public final String errorMsg;

        public FailedCall(Long id, int newRetryCount, long backoffSeconds, String errorMsg) {
            this.id = id;
            this.newRetryCount = newRetryCount;
            this.backoffSeconds = backoffSeconds;
            this.errorMsg = errorMsg;
        }
    }

    public static final class DeadCall {

        public final Long id;
        public final int newRetryCount;
        public final DeadReason reason;
        public final String errorMsg;

        public DeadCall(Long id, int newRetryCount, DeadReason reason, String errorMsg) {
            this.id = id;
            this.newRetryCount = newRetryCount;
            this.reason = reason;
            this.errorMsg = errorMsg;
        }
    }
}
