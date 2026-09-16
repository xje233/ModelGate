package io.modelgate.cache;

import reactor.core.publisher.Mono;

/**
 * Vector-similarity response cache. Two backends share this interface:
 * in-process (single instance, no dependency) and Redis (shared across replicas).
 *
 * <p>{@code payload} is an opaque string supplied by the caller — this module never
 * interprets it, which keeps serialization concerns in the gateway layer.
 */
public interface SemanticCache {

    /**
     * Best match within {@code namespace} whose similarity reaches {@code threshold}.
     * The namespace is the isolation boundary and must encode key/tenant/model, so one
     * caller's answer can never be served to another.
     */
    Mono<CacheLookup> find(String namespace, double[] embedding, double threshold);

    Mono<Void> put(String namespace, String entryId, double[] embedding, String payload);

    /** Diagnostics: entries currently held in a namespace. */
    Mono<Long> size(String namespace);

    Mono<Void> clear();
}
