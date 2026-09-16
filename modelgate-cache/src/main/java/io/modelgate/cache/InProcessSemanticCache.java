package io.modelgate.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Mono;

/**
 * Single-instance cache: per-namespace LRU map with TTL. Lookup is a linear scan bounded by
 * {@code maxEntriesPerNamespace} — 64 dims × 200 entries is well under a millisecond, and it
 * keeps the correctness story obvious. A shared deployment would use the Redis backend.
 */
public final class InProcessSemanticCache implements SemanticCache {

    private record Entry(double[] embedding, String payload, long createdAtMillis) {
    }

    private final Map<String, LinkedHashMap<String, Entry>> namespaces = new ConcurrentHashMap<>();
    private final int maxEntriesPerNamespace;
    private final long ttlMillis;

    public InProcessSemanticCache(int maxEntriesPerNamespace, long ttlSeconds) {
        this.maxEntriesPerNamespace = maxEntriesPerNamespace;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    @Override
    public Mono<CacheLookup> find(String namespace, double[] embedding, double threshold) {
        return Mono.fromCallable(() -> {
            Map<String, Entry> entries = namespaces.get(namespace);
            if (entries == null) {
                return CacheLookup.miss();
            }
            long now = System.currentTimeMillis();
            String bestId = null;
            String bestPayload = null;
            double bestScore = -1;
            synchronized (entries) {
                Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Entry> candidate = it.next();
                    if (now - candidate.getValue().createdAtMillis() > ttlMillis) {
                        it.remove();
                        continue;
                    }
                    double score = CosineSimilarity.of(embedding, candidate.getValue().embedding());
                    if (score > bestScore) {
                        bestScore = score;
                        bestId = candidate.getKey();
                        bestPayload = candidate.getValue().payload();
                    }
                }
            }
            return bestId != null && bestScore >= threshold
                    ? CacheLookup.hit(bestScore, bestId, bestPayload)
                    : CacheLookup.miss();
        });
    }

    @Override
    public Mono<Void> put(String namespace, String entryId, double[] embedding, String payload) {
        return Mono.fromRunnable(() -> {
            Map<String, Entry> entries = namespaces.computeIfAbsent(namespace,
                    k -> new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                            return size() > maxEntriesPerNamespace;
                        }
                    });
            synchronized (entries) {
                entries.put(entryId, new Entry(embedding, payload, System.currentTimeMillis()));
            }
        });
    }

    @Override
    public Mono<Long> size(String namespace) {
        return Mono.fromCallable(() -> {
            Map<String, Entry> entries = namespaces.get(namespace);
            if (entries == null) {
                return 0L;
            }
            synchronized (entries) {
                return (long) entries.size();
            }
        });
    }

    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(namespaces::clear);
    }
}
