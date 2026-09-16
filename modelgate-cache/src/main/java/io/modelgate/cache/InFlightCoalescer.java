package io.modelgate.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * Collapses concurrent identical work into a single execution (the "cache stampede" /
 * thundering-herd guard).
 *
 * <p>When N identical requests miss the cache at the same moment, only the first triggers the
 * upstream call; the rest subscribe to the same {@code Mono} and receive the same result.
 * This is deliberately limited to single-value results: sharing a stream would risk
 * duplicating output, so streaming requests never go through here.
 */
public final class InFlightCoalescer {

    private final ConcurrentHashMap<String, Mono<?>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong joined = new AtomicLong();

    @SuppressWarnings("unchecked")
    public <T> Mono<T> coalesce(String key, Supplier<Mono<T>> work) {
        return coalesce(key, work, () -> {
        });
    }

    /**
     * @param onJoined invoked when this caller folded into an execution someone else started —
     *                 the hook the metrics use to prove the stampede guard is doing its job
     */
    @SuppressWarnings("unchecked")
    public <T> Mono<T> coalesce(String key, Supplier<Mono<T>> work, Runnable onJoined) {
        java.util.concurrent.atomic.AtomicBoolean created =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Mono<?> mono = inFlight.computeIfAbsent(key, k -> {
            created.set(true);
            return work.get().doFinally(signal -> inFlight.remove(k)).cache();
        });
        if (!created.get()) {
            joined.incrementAndGet();
            onJoined.run();
        }
        return (Mono<T>) mono;
    }

    /** How many callers were folded into someone else's execution — the stampede it prevented. */
    public long joinedCount() {
        return joined.get();
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
