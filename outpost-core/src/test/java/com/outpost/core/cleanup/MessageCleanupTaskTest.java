package com.outpost.core.cleanup;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.support.InMemoryOutpostAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCleanupTaskTest {

    private InMemoryOutpostAdminRepository repository;
    private OutpostProperties properties;
    private MessageCleanupTask task;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutpostAdminRepository();
        properties = new OutpostProperties();
        task = new MessageCleanupTask(repository, properties);
    }

    @Test
    void disabledCleanupDoesNotTouchDatabase() {
        properties.getCleanup().setEnabled(false);
        repository.expiredIds.add(1L);

        assertEquals(0, task.runOnce());
        assertTrue(repository.deleteCalls.isEmpty());
        assertEquals(1, repository.expiredIds.size());
    }

    @Test
    void cleansExpiredRowsInPrimaryKeyBatches() {
        properties.getCleanup().setBatchSize(2);
        repository.maxId = 100L;
        repository.expiredIds.addAll(Arrays.asList(1L, 2L, 3L));
        for (long id : Arrays.asList(1L, 2L, 3L)) {
            repository.rows.add(record(id, 2));
        }

        assertEquals(3, task.runOnce());
        assertEquals(3, repository.deleteCalls.size());
        InMemoryOutpostAdminRepository.DeleteCall first = repository.deleteCalls.get(0);
        assertEquals(100L, first.maxId);
        assertEquals(3, first.successRetainDays);
        assertEquals(30, first.deadRetainDays);
        assertEquals(2, first.batchSize);
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void keepsFailedAndProcessingRows() {
        repository.expiredIds.add(1L);
        OutboxMessageRecord success = record(1L, 2);
        OutboxMessageRecord failed = record(2L, 3);
        OutboxMessageRecord processing = record(3L, 1);
        repository.rows.add(success);
        repository.rows.add(failed);
        repository.rows.add(processing);

        assertEquals(1, task.runOnce());
        assertEquals(2, repository.rows.size());
        assertEquals(3, repository.rows.get(0).getStatus());
        assertEquals(1, repository.rows.get(1).getStatus());
    }

    private static OutboxMessageRecord record(long id, int status) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType("cache_delete");
        record.setBizKey("order:" + id);
        record.setPayload("order:" + id);
        record.setStatus(status);
        record.setCreateTime(LocalDateTime.now());
        return record;
    }
}
