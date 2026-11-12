package com.outpost.core.admin;

import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.config.OutpostPropertiesValidator;
import com.outpost.core.delivery.DeliveryHandlerRegistry;
import com.outpost.core.message.OutboxMessageRecord;
import com.outpost.core.retry.DefaultBackoffPolicy;
import com.outpost.core.retry.DynamicBackoffPolicy;
import com.outpost.core.support.InMemoryOutpostAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOutpostAdminServiceTest {

    private InMemoryOutpostAdminRepository repository;
    private OutpostProperties properties;
    private DynamicBackoffPolicy backoffPolicy;
    private DefaultOutpostAdminService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutpostAdminRepository();
        properties = new OutpostProperties();
        backoffPolicy = new DynamicBackoffPolicy(
                new DefaultBackoffPolicy(new long[]{1L, 3L, 10L}));
        service = new DefaultOutpostAdminService(repository, properties, backoffPolicy,
                null, null, null);
    }

    @Test
    void queriesFilterAuxiliaryMessagesByDefault() {
        repository.rows.add(record(1L, "order:1", 0, 0));
        repository.rows.add(record(2L, "order:1", 1, 0));

        OutpostPage<?> page = service.queryMessages(new MessageQuery());
        assertEquals(1, page.getItems().size());
        assertEquals(1L, page.getTotal());
        assertFalse(repository.lastQuery.isIncludeAuxiliary());

        MessageQuery including = new MessageQuery();
        including.setIncludeAuxiliary(true);
        assertEquals(2, service.queryMessages(including).getItems().size());
    }

    @Test
    void deadQueryForcesDeadStatusAndReason() {
        OutboxMessageRecord dead = record(1L, "order:1", 0, 4);
        dead.setDeadReason(1);
        repository.rows.add(dead);
        repository.rows.add(record(2L, "order:2", 0, 0));

        DeadMessageQuery query = new DeadMessageQuery();
        query.setDeadReason(1);
        assertEquals(1, service.queryDeadMessages(query).getItems().size());
        assertEquals(4, repository.lastQuery.getStatus().intValue());
    }

    @Test
    void resendResetsStateAndBumpsVersion() {
        OutboxMessageRecord dead = record(7L, "order:7", 0, 4);
        dead.setRetryCount(3);
        dead.setErrorMsg("boom");
        dead.setDeadReason(2);
        dead.setVersion(2);
        repository.rows.add(dead);

        service.resendMessage(7L);

        assertEquals(0, dead.getStatus());
        assertEquals(0, dead.getRetryCount());
        assertNull(dead.getErrorMsg());
        assertNull(dead.getDeadReason());
        assertEquals(3, dead.getVersion());
    }

    @Test
    void batchResendRejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchResendMessages(Collections.<Long>singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchResendMessages(Collections.<Long>emptyList()));
    }

    @Test
    void statsExcludeAuxiliaryMessages() {
        repository.rows.add(record(1L, "a", 0, 0));
        repository.rows.add(record(2L, "b", 0, 4));
        repository.rows.add(record(3L, "c", 1, 4));
        OutpostStats stats = service.getStats();
        assertEquals(2L, stats.getTotal());
        assertEquals(1L, stats.getDead());
    }

    @Test
    void updateConfigAppliesWhitelistAndRefreshesBackoff() {
        service.updateConfig(new OutpostConfig()
                .set(OutpostConfig.CLEANUP_ENABLED, false)
                .set(OutpostConfig.ENABLED_NAMESPACES, Arrays.asList("order", "user"))
                .set(OutpostConfig.DOUBLE_DELETE_ENABLED, true)
                .set(OutpostConfig.DOUBLE_DELETE_DELAY_MILLIS, 2000L)
                .set(OutpostConfig.BACKOFF_STEPS, new long[]{2L, 5L, 20L}));

        assertFalse(properties.getCleanup().isEnabled());
        assertEquals(Arrays.asList("order", "user"),
                properties.getDeliveryCache().getEnabledNamespaces());
        assertTrue(properties.getDeliveryCache().getDoubleDelete().isEnabled());
        assertEquals(2000L, properties.getDeliveryCache().getDoubleDelete().getDelayMillis());
        assertEquals(2L, backoffPolicy.nextDelaySeconds(1));
        assertEquals(20L, backoffPolicy.nextDelaySeconds(3));
    }

    @Test
    void updateConfigRejectsUnsupportedKeyWithClearMessage() {
        OutpostConfigurationException error = assertThrows(OutpostConfigurationException.class,
                () -> service.updateConfig(
                        new OutpostConfig().set("schedule.fixed-delay-ms", 1L)));
        assertTrue(error.getMessage().contains("不支持热更"));
        assertTrue(error.getMessage().contains("schedule.fixed-delay-ms"));
        assertEquals(5000L, properties.getSchedule().getFixedDelayMs());
    }

    @Test
    void updateConfigRollsBackCompletelyWhenCouplingFormulaFails() {
        assertThrows(OutpostConfigurationException.class, () -> service.updateConfig(
                new OutpostConfig()
                        .set(OutpostConfig.BATCH_SIZE, 1000)
                        .set(OutpostConfig.CLEANUP_ENABLED, false)));

        assertEquals(100, properties.getSchedule().getBatchSize());
        assertTrue(properties.getCleanup().isEnabled());
    }

    @Test
    void updateConfigRejectsNonMonotonicBackoff() {
        assertThrows(IllegalArgumentException.class, () -> service.updateConfig(
                new OutpostConfig().set(OutpostConfig.BACKOFF_STEPS, new long[]{5L, 3L})));
        assertEquals(1L, backoffPolicy.nextDelaySeconds(1));
    }

    @Test
    void validatorPathRejectsTooLargeBatchSize() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> null);
        DefaultOutpostAdminService withValidator = new DefaultOutpostAdminService(
                repository, properties, backoffPolicy, new OutpostPropertiesValidator(),
                new DeliveryHandlerRegistry(Collections.emptyList()), dataSource);

        assertThrows(OutpostConfigurationException.class, () -> withValidator.updateConfig(
                new OutpostConfig().set(OutpostConfig.BATCH_SIZE, 1000)));
        assertEquals(100, properties.getSchedule().getBatchSize());
    }

    private static OutboxMessageRecord record(long id, String bizKey, int messageType, int status) {
        OutboxMessageRecord record = new OutboxMessageRecord();
        record.setId(id);
        record.setBizType("cache_delete");
        record.setBizKey(bizKey);
        record.setPayload(bizKey);
        record.setMessageType(messageType);
        record.setStatus(status);
        record.setCreateTime(java.time.LocalDateTime.now());
        return record;
    }
}
