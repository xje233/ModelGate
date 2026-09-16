package io.modelgate.cache;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Mono;

/**
 * Redis-backed cache, shared by every gateway replica.
 *
 * <p>Layout per namespace (three keys, all TTL'd together):
 * <ul>
 *   <li>{@code <ns>:vec}  hash entryId -&gt; base64(float32 vector) — the only thing read on a lookup</li>
 *   <li>{@code <ns>:data} hash entryId -&gt; payload — fetched only when a vector wins</li>
 *   <li>{@code <ns>:idx}  zset entryId -&gt; createdAt — drives trimming to {@code maxEntries}</li>
 * </ul>
 *
 * <p>Trade-off worth stating plainly: retrieval is a linear scan over the namespace, so cost
 * grows with entries. That is why the entry count is capped and why the production path for a
 * large corpus is a real vector index (Redis Stack VSS / Qdrant) behind this same interface.
 */
public final class RedisSemanticCache implements SemanticCache {

    private final ReactiveStringRedisTemplate redis;
    private final int maxEntries;
    private final long ttlSeconds;

    public RedisSemanticCache(ReactiveStringRedisTemplate redis, int maxEntries, long ttlSeconds) {
        this.redis = redis;
        this.maxEntries = maxEntries;
        this.ttlSeconds = ttlSeconds;
    }

    private static String vecKey(String ns) {
        return ns + ":vec";
    }

    private static String dataKey(String ns) {
        return ns + ":data";
    }

    private static String idxKey(String ns) {
        return ns + ":idx";
    }

    @Override
    public Mono<CacheLookup> find(String namespace, double[] embedding, double threshold) {
        return redis.<String, String>opsForHash().entries(vecKey(namespace))
                .reduce(Best.EMPTY, (best, candidate) -> {
                    double score = CosineSimilarity.of(embedding, VectorCodec.decode(candidate.getValue()));
                    return score > best.score ? new Best(candidate.getKey(), score) : best;
                })
                .flatMap(best -> {
                    if (best.id == null || best.score < threshold) {
                        return Mono.just(CacheLookup.miss());
                    }
                    return redis.<String, String>opsForHash().get(dataKey(namespace), best.id)
                            .map(payload -> CacheLookup.hit(best.score, best.id, payload))
                            .defaultIfEmpty(CacheLookup.miss());
                });
    }

    private record Best(String id, double score) {
        static final Best EMPTY = new Best(null, -1);
    }

    @Override
    public Mono<Void> put(String namespace, String entryId, double[] embedding, String payload) {
        long now = System.currentTimeMillis();
        return redis.<String, String>opsForHash().put(vecKey(namespace), entryId, VectorCodec.encode(embedding))
                .then(redis.<String, String>opsForHash().put(dataKey(namespace), entryId, payload))
                .then(redis.opsForZSet().add(idxKey(namespace), entryId, now))
                .then(trim(namespace))
                .then(expire(namespace))
                .then();
    }

    private Mono<Void> trim(String namespace) {
        return redis.opsForZSet().size(idxKey(namespace))
                .flatMap(size -> {
                    long excess = size - maxEntries;
                    if (excess <= 0) {
                        return Mono.empty();
                    }
                    return redis.opsForZSet().range(idxKey(namespace), Range.closed(0L, excess - 1))
                            .collectList()
                            .flatMap(stale -> {
                                if (stale.isEmpty()) {
                                    return Mono.empty();
                                }
                                Object[] ids = stale.toArray();
                                return redis.<String, String>opsForHash().remove(vecKey(namespace), ids)
                                        .then(redis.<String, String>opsForHash().remove(dataKey(namespace), ids))
                                        .then(redis.opsForZSet().remove(idxKey(namespace), ids))
                                        .then();
                            });
                })
                .then();
    }

    private Mono<Boolean> expire(String namespace) {
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        return redis.expire(vecKey(namespace), ttl)
                .then(redis.expire(dataKey(namespace), ttl))
                .then(redis.expire(idxKey(namespace), ttl));
    }

    @Override
    public Mono<Long> size(String namespace) {
        return redis.opsForZSet().size(idxKey(namespace)).defaultIfEmpty(0L);
    }

    @Override
    public Mono<Void> clear() {
        return redis.keys("sc:*")
                .flatMap(redis::delete)
                .then();
    }
}
