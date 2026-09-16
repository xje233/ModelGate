package io.modelgate.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import reactor.core.publisher.Mono;

/**
 * Default store: keeps the last N receipts in a ring and aggregates them in memory.
 * Enough to run and demo the whole cost-accounting path without a database, and it makes the
 * "which tenant spent what" question answerable in a single-node dev setup.
 */
public final class InMemoryUsageStore implements UsageStore {

    private static final class Acc {
        long requests;
        long prompt;
        long completion;
        long cached;
        long hits;
        BigDecimal cost = BigDecimal.ZERO;

        void add(UsageRecord r) {
            requests++;
            prompt += r.promptTokens();
            completion += r.completionTokens();
            cached += r.cachedPromptTokens();
            hits += r.cacheHit() ? 1 : 0;
            cost = cost.add(r.costUsd() == null ? BigDecimal.ZERO : r.costUsd());
        }
    }

    private final ArrayDeque<UsageRecord> records = new ArrayDeque<>();
    private final int maxRecords;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public InMemoryUsageStore(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    @Override
    public void record(UsageRecord record) {
        synchronized (records) {
            if (records.size() >= maxRecords) {
                records.pollFirst();
                dropped.incrementAndGet();
            }
            records.addLast(record);
        }
        written.incrementAndGet();
    }

    @Override
    public Mono<List<UsageAggregate>> aggregate(UsageQuery query) {
        return Mono.fromCallable(() -> {
            Map<String, Acc> grouped = new LinkedHashMap<>();
            synchronized (records) {
                for (UsageRecord record : records) {
                    if (!inRange(record, query)) {
                        continue;
                    }
                    grouped.computeIfAbsent(subjectOf(record, query.dimension()), k -> new Acc())
                            .add(record);
                }
            }
            List<UsageAggregate> result = new ArrayList<>(grouped.size());
            grouped.forEach((group, acc) -> result.add(new UsageAggregate(
                    group, acc.requests, acc.prompt, acc.completion, acc.cached, acc.hits, acc.cost)));
            return result;
        });
    }

    @Override
    public Mono<UsageStoreStats> stats() {
        synchronized (records) {
            return Mono.just(new UsageStoreStats(records.size(), written.get(), dropped.get()));
        }
    }

    @Override
    public Mono<Void> flush() {
        return Mono.empty();
    }

    private static boolean inRange(UsageRecord record, UsageQuery query) {
        LocalDate date = record.usageDate();
        return date != null
                && (query.from() == null || !date.isBefore(query.from()))
                && (query.to() == null || !date.isAfter(query.to()));
    }

    private static String subjectOf(UsageRecord record, UsageDimension dimension) {
        return switch (dimension) {
            case DATE -> record.usageDate() == null ? "" : record.usageDate().toString();
            case TENANT -> nullSafe(record.tenantId());
            case MODEL -> nullSafe(record.modelGroup());
            case KEY -> nullSafe(record.keyId());
            case ARM -> nullSafe(record.arm());
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
