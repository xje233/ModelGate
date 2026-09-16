package io.modelgate.usage;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Usage accounting.
 *
 * <p>{@link #record} is deliberately <b>non-blocking and lossy under extreme pressure</b>:
 * accounting must never add latency to a user request, so records go into a bounded queue and
 * are dropped (and counted) when it is full. Losing a receipt under a flood is a far smaller
 * problem than making every caller wait on a database.
 */
public interface UsageStore {

    void record(UsageRecord record);

    Mono<List<UsageAggregate>> aggregate(UsageQuery query);

    Mono<UsageStoreStats> stats();

    /** Force a synchronous drain — used on shutdown and by tests. */
    Mono<Void> flush();
}
