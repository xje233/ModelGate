package io.modelgate.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class InMemoryUsageStoreTest {

    private static UsageRecord record(String requestId, String tenant, String key, String model,
                                      LocalDate date, int prompt, int completion,
                                      boolean cacheHit, String cost) {
        return new UsageRecord(requestId, key, tenant, model, model, "mock",
                prompt, completion, 0, new BigDecimal(cost), cacheHit, false, "single",
                "mock-a", "success", date, 0, 0, 10);
    }

    private static Map<String, UsageAggregate> byGroup(List<UsageAggregate> aggregates) {
        return aggregates.stream().collect(Collectors.toMap(UsageAggregate::group, Function.identity()));
    }

    @Test
    void aggregatesByTenant() {
        InMemoryUsageStore store = new InMemoryUsageStore(100);
        LocalDate today = LocalDate.of(2026, 9, 16);
        store.record(record("r1", "tenant-a", "key-a", "fast-medium", today, 10, 20, false, "0.001"));
        store.record(record("r2", "tenant-a", "key-b", "fast-medium", today, 5, 5, false, "0.002"));
        store.record(record("r3", "tenant-b", "key-c", "smart", today, 1, 1, true, "0.000"));

        Map<String, UsageAggregate> result = byGroup(store.aggregate(
                UsageQuery.of(UsageDimension.TENANT, today, today)).block());

        assertEquals(2, result.size());
        UsageAggregate tenantA = result.get("tenant-a");
        assertEquals(2, tenantA.requests());
        assertEquals(15, tenantA.promptTokens());
        assertEquals(25, tenantA.completionTokens());
        assertEquals(40, tenantA.totalTokens());
        assertEquals(0, new BigDecimal("0.003").compareTo(tenantA.costUsd()));
        assertEquals(0, tenantA.cacheHitRate());
        assertEquals(1, result.get("tenant-b").cacheHits());
        assertEquals(1.0, result.get("tenant-b").cacheHitRate());
    }

    @Test
    void aggregatesByModelAndKeyAndDate() {
        InMemoryUsageStore store = new InMemoryUsageStore(100);
        LocalDate day1 = LocalDate.of(2026, 9, 15);
        LocalDate day2 = LocalDate.of(2026, 9, 16);
        store.record(record("r1", "t", "key-a", "fast-medium", day1, 10, 0, false, "0.001"));
        store.record(record("r2", "t", "key-a", "smart", day2, 10, 0, false, "0.001"));
        store.record(record("r3", "t", "key-b", "smart", day2, 10, 0, false, "0.001"));

        assertEquals(2, byGroup(store.aggregate(
                UsageQuery.of(UsageDimension.MODEL, day1, day2)).block()).size());
        assertEquals(2, byGroup(store.aggregate(
                UsageQuery.of(UsageDimension.KEY, day1, day2)).block()).size());
        assertEquals(2, byGroup(store.aggregate(
                UsageQuery.of(UsageDimension.DATE, day1, day2)).block()).size());
    }

    @Test
    void dateRangeFiltersRecords() {
        InMemoryUsageStore store = new InMemoryUsageStore(100);
        LocalDate day1 = LocalDate.of(2026, 9, 15);
        LocalDate day2 = LocalDate.of(2026, 9, 16);
        store.record(record("r1", "t", "k", "m", day1, 1, 1, false, "0.001"));
        store.record(record("r2", "t", "k", "m", day2, 1, 1, false, "0.001"));

        List<UsageAggregate> onlyDay2 = store.aggregate(
                UsageQuery.of(UsageDimension.TENANT, day2, day2)).block();
        assertEquals(1, onlyDay2.size());
        assertEquals(1, onlyDay2.get(0).requests());
    }

    @Test
    void capacityIsBoundedAndOverflowIsCounted() {
        InMemoryUsageStore store = new InMemoryUsageStore(3);
        LocalDate today = LocalDate.of(2026, 9, 16);
        for (int i = 0; i < 5; i++) {
            store.record(record("r" + i, "t", "k", "m", today, 1, 1, false, "0.001"));
        }
        UsageStoreStats stats = store.stats().block();
        assertEquals(3, stats.queued(), "ring keeps only the newest records");
        assertEquals(5, stats.written());
        assertEquals(2, stats.dropped(), "dropped receipts are the alertable signal");
        assertEquals(3, store.aggregate(UsageQuery.of(UsageDimension.TENANT, today, today))
                .block().get(0).requests());
    }

    @Test
    void emptyStoreReturnsNoRows() {
        InMemoryUsageStore store = new InMemoryUsageStore(10);
        assertTrue(store.aggregate(UsageQuery.of(UsageDimension.TENANT,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).block().isEmpty());
    }
}
