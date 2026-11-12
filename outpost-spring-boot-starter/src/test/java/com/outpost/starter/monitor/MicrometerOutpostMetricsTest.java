package com.outpost.starter.monitor;

import com.outpost.core.message.DeadReason;
import com.outpost.core.message.OutpostMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MicrometerOutpostMetricsTest {

    @Test
    void registersAllEightFixedMeterNames() {
        OutpostMessageRepository repository = Mockito.mock(OutpostMessageRepository.class);
        Mockito.when(repository.countDead()).thenReturn(3L);
        Mockito.when(repository.countAll()).thenReturn(10L);
        Mockito.when(repository.countRedelivery()).thenReturn(2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        MicrometerOutpostMetrics metrics = new MicrometerOutpostMetrics(registry, repository);
        metrics.onMessageProduced(2);
        metrics.onDeliverySuccess("cache_delete", 5L);
        metrics.onDeliveryFail("cache_delete", 7L);
        metrics.onDead("cache_delete", DeadReason.RETRY_EXHAUSTED);
        metrics.onScheduleDuration(11L);
        metrics.onRetry(3);

        assertEquals(2D, registry.find(MicrometerOutpostMetrics.PRODUCE_TOTAL).counter().count());
        assertNotNull(registry.find(MicrometerOutpostMetrics.DELIVERY_SUCCESS_TOTAL).counter());
        assertNotNull(registry.find(MicrometerOutpostMetrics.DELIVERY_FAIL_TOTAL).counter());
        assertEquals(4D, registry.find(MicrometerOutpostMetrics.DEAD_GAUGE).gauge().value());
        assertEquals(0.2D, registry.find(MicrometerOutpostMetrics.REDELIVERY_RATIO).gauge().value(), 1e-9);
        assertEquals(1L, registry.find(MicrometerOutpostMetrics.SCHEDULE_DURATION_MS).timer().count());
        assertEquals(2L, registry.find(MicrometerOutpostMetrics.DELIVERY_DURATION_MS).timer().count());
        assertEquals(1L, registry.find(MicrometerOutpostMetrics.RETRY_COUNT_DISTRIBUTION)
                .summary().count());
    }
}
