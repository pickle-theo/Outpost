package com.outpost.core.support;

import com.outpost.core.admin.MessageQuery;
import com.outpost.core.admin.OutpostAdminRepository;
import com.outpost.core.admin.OutpostStats;
import com.outpost.core.message.OutboxMessageRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运维/清理测试用的内存桩仓储。
 */
public class InMemoryOutpostAdminRepository extends InMemoryOutpostMessageRepository
        implements OutpostAdminRepository {

    public final List<OutboxMessageRecord> rows = new ArrayList<OutboxMessageRecord>();
    public final List<DeleteCall> deleteCalls = new ArrayList<DeleteCall>();
    public final Set<Long> expiredIds = new LinkedHashSet<Long>();

    public volatile long maxId = 100L;
    public volatile int queryCount;
    public volatile MessageQuery lastQuery;

    @Override
    public long countMessages(MessageQuery query) {
        queryCount++;
        lastQuery = query;
        return filter(query).size();
    }

    @Override
    public List<OutboxMessageRecord> selectMessages(MessageQuery query) {
        queryCount++;
        lastQuery = query;
        List<OutboxMessageRecord> filtered = filter(query);
        int from = (int) query.offset();
        if (from >= filtered.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<OutboxMessageRecord>(
                filtered.subList(from, Math.min(from + query.getPageSize(), filtered.size())));
    }

    @Override
    public int resendMessages(List<Long> ids) {
        int affected = 0;
        for (OutboxMessageRecord row : rows) {
            if (ids.contains(row.getId()) && (row.getStatus() == 2 || row.getStatus() == 3
                    || row.getStatus() == 4)) {
                row.setStatus(0);
                row.setRetryCount(0);
                row.setErrorMsg(null);
                row.setDeadReason(null);
                row.setVersion(row.getVersion() + 1);
                affected++;
            }
        }
        return affected;
    }

    @Override
    public OutpostStats stats() {
        OutpostStats stats = new OutpostStats();
        for (OutboxMessageRecord row : rows) {
            if (row.getMessageType() == 1) {
                continue;
            }
            stats.setTotal(stats.getTotal() + 1);
            if (row.getStatus() == 0) {
                stats.setPending(stats.getPending() + 1);
            } else if (row.getStatus() == 4) {
                stats.setDead(stats.getDead() + 1);
            }
        }
        return stats;
    }

    @Override
    public long maxMessageId() {
        return maxId;
    }

    @Override
    public int deleteExpired(long maxId, int successRetainDays, int deadRetainDays, int batchSize) {
        deleteCalls.add(new DeleteCall(maxId, successRetainDays, deadRetainDays, batchSize));
        List<Long> toDelete = new ArrayList<Long>();
        for (Long id : expiredIds) {
            if (id <= maxId && toDelete.size() < batchSize) {
                toDelete.add(id);
            }
        }
        for (Long id : toDelete) {
            expiredIds.remove(id);
            List<OutboxMessageRecord> remaining = new ArrayList<OutboxMessageRecord>();
            for (OutboxMessageRecord row : rows) {
                if (!id.equals(row.getId())) {
                    remaining.add(row);
                }
            }
            rows.clear();
            rows.addAll(remaining);
        }
        return toDelete.size();
    }

    private List<OutboxMessageRecord> filter(MessageQuery query) {
        List<OutboxMessageRecord> result = new ArrayList<OutboxMessageRecord>();
        for (OutboxMessageRecord row : rows) {
            if (!query.isIncludeAuxiliary() && row.getMessageType() == 1) {
                continue;
            }
            if (query.getStatus() != null && query.getStatus() != row.getStatus()) {
                continue;
            }
            if (query.getDeadReason() != null
                    && !query.getDeadReason().equals(row.getDeadReason())) {
                continue;
            }
            if (query.getBizType() != null && !query.getBizType().equals(row.getBizType())) {
                continue;
            }
            if (query.getBizKey() != null && !query.getBizKey().equals(row.getBizKey())) {
                continue;
            }
            result.add(row);
        }
        return result;
    }

    public static final class DeleteCall {

        public final long maxId;
        public final int successRetainDays;
        public final int deadRetainDays;
        public final int batchSize;

        public DeleteCall(long maxId, int successRetainDays, int deadRetainDays, int batchSize) {
            this.maxId = maxId;
            this.successRetainDays = successRetainDays;
            this.deadRetainDays = deadRetainDays;
            this.batchSize = batchSize;
        }
    }
}
