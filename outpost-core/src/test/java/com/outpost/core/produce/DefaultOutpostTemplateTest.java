package com.outpost.core.produce;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.message.MessageStatus;
import com.outpost.core.message.MessageType;
import com.outpost.core.message.OutboxMessageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOutpostTemplateTest {

    private DataSource dataSource;
    private OutpostProperties properties;
    private TransactionTestSupport.RecordingRepository repository;
    private DefaultOutpostTemplate template;

    @BeforeEach
    void setUp() {
        dataSource = TransactionTestSupport.newDataSource();
        properties = new OutpostProperties();
        repository = new TransactionTestSupport.RecordingRepository();
        TransactionMessageCollector collector =
                new TransactionMessageCollector(repository, dataSource, properties);
        template = new DefaultOutpostTemplate(collector, new NoopTransactionManager(), properties);
    }

    @AfterEach
    void tearDown() {
        TransactionTestSupport.reset();
    }

    @Test
    void shouldRegisterTwoMessagesPerKeyWhenDoubleDeleteEnabled() {
        properties.getDeliveryCache().getDoubleDelete().setEnabled(true);
        properties.getDeliveryCache().getDoubleDelete().setDelayMillis(1000L);
        TransactionTestSupport.begin(dataSource);

        template.registerCacheInvalidate("order", Arrays.asList("k1", "k2"));
        TransactionTestSupport.triggerBeforeCommit();

        List<OutboxMessageRecord> records = repository.allRecords();
        assertEquals(4, records.size());

        assertNormalCacheMessage(records.get(0), "order:k1", "k1");
        assertAuxiliaryCacheMessage(records.get(1), "order:k1", "k1");
        assertNormalCacheMessage(records.get(2), "order:k2", "k2");
        assertAuxiliaryCacheMessage(records.get(3), "order:k2", "k2");
    }

    @Test
    void shouldRegisterOnlyNormalMessagesInTagMode() {
        properties.getDeliveryCache().setHandlerType(CacheBizTypes.CACHE_TAG_DELETE);
        properties.getDeliveryCache().getDoubleDelete().setEnabled(true);
        TransactionTestSupport.begin(dataSource);

        template.registerCacheInvalidate("order", Arrays.asList("k1", "k2"));
        TransactionTestSupport.triggerBeforeCommit();

        List<OutboxMessageRecord> records = repository.allRecords();
        assertEquals(2, records.size());
        for (OutboxMessageRecord record : records) {
            assertEquals(CacheBizTypes.CACHE_TAG_DELETE, record.getBizType());
            assertEquals(MessageType.NORMAL.code(), record.getMessageType());
        }
        assertEquals("order:k1", records.get(0).getBizKey());
        assertEquals("k1", records.get(0).getPayload());
        assertEquals("order:k2", records.get(1).getBizKey());
    }

    @Test
    void shouldReturnWithoutRegisteringWhenKeysEmpty() {
        template.registerCacheInvalidate("order", null);
        template.registerCacheInvalidate("order", Collections.<String>emptyList());

        assertTrue(repository.batches.isEmpty());
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> template.registerCacheInvalidate(" ", Arrays.asList("k1")));
        assertThrows(IllegalArgumentException.class,
                () -> template.registerCacheInvalidate(null, Arrays.asList("k1")));
        assertThrows(IllegalArgumentException.class,
                () -> template.registerCacheInvalidate("order", Arrays.asList("k1", null)));
        assertThrows(IllegalArgumentException.class,
                () -> template.registerCacheInvalidate("order", Arrays.asList("k1", "  ")));
    }

    @Test
    void shouldRegisterNormalMessageWithGlobalMaxRetry() {
        TransactionTestSupport.begin(dataSource);

        template.registerMessage("order", "o-1", "{}");
        TransactionTestSupport.triggerBeforeCommit();

        OutboxMessageRecord record = repository.allRecords().get(0);
        assertEquals("order", record.getBizType());
        assertEquals("o-1", record.getBizKey());
        assertEquals(properties.getRetry().getMaxRetryCount(), record.getMaxRetry());
        assertEquals(MessageStatus.PENDING.code(), record.getStatus());
    }

    @Test
    void shouldExecuteBusinessActionInTransaction() {
        String result = template.executeInTransaction(() -> "done");

        assertEquals("done", result);
        assertThrows(IllegalArgumentException.class,
                () -> template.executeInTransaction(null));
    }

    private void assertNormalCacheMessage(OutboxMessageRecord record, String bizKey, String payload) {
        assertEquals(CacheBizTypes.CACHE_DELETE, record.getBizType());
        assertEquals(bizKey, record.getBizKey());
        assertEquals(payload, record.getPayload());
        assertEquals(MessageType.NORMAL.code(), record.getMessageType());
        assertEquals(properties.getRetry().getMaxRetryCount(), record.getMaxRetry());
        assertTrue(record.getNextRetryTime().isBefore(java.time.LocalDateTime.now().plusSeconds(1)));
    }

    private void assertAuxiliaryCacheMessage(OutboxMessageRecord record, String bizKey, String payload) {
        assertEquals(CacheBizTypes.CACHE_DELETE, record.getBizType());
        assertEquals(bizKey, record.getBizKey());
        assertEquals(payload, record.getPayload());
        assertEquals(MessageType.AUXILIARY.code(), record.getMessageType());
        assertEquals(1, record.getMaxRetry());
        assertTrue(record.getNextRetryTime().isAfter(java.time.LocalDateTime.now().plusNanos(500_000_000L)));
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return null;
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    }
}
