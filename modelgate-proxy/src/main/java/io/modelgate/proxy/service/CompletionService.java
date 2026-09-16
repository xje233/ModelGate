package io.modelgate.proxy.service;

import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

import io.modelgate.cache.CacheLookup;
import io.modelgate.cache.EmbeddingCache;
import io.modelgate.cache.InFlightCoalescer;
import io.modelgate.cache.SemanticCache;
import io.modelgate.client.CostCalculator;
import io.modelgate.client.LlmClient;
import io.modelgate.core.ChatRequest;
import io.modelgate.core.ChatResponse;
import io.modelgate.core.Deployment;
import io.modelgate.providers.json.Json;
import io.modelgate.proxy.config.CacheProperties;
import io.modelgate.proxy.metrics.MetricsRecorder;
import io.modelgate.proxy.security.ApiKeyIdentity;
import io.modelgate.router.RouterService;

/**
 * Non-streaming completion path, cache-first.
 *
 * <pre>
 *   embedding(cached by prompt hash) -> semantic lookup
 *        hit  -> return cached answer, count saved tokens/cost
 *        miss -> upstream (with failover) -> store in cache (fire and forget)
 * </pre>
 *
 * <p>Two deliberate properties:
 * <ul>
 *   <li><b>The cache can never break a request.</b> Any embedding/lookup error degrades to the
 *       normal upstream path and bumps a degraded counter.</li>
 *   <li><b>Concurrent identical requests collapse into one upstream call</b>
 *       ({@link InFlightCoalescer}) — the stampede guard for a cold cache.</li>
 * </ul>
 */
public final class CompletionService {

    private static final Logger log = LoggerFactory.getLogger(CompletionService.class);

    private final GatewayService gateway;
    private final LlmClient client;
    private final RouterService router;
    private final SemanticCache cache;
    private final EmbeddingCache embeddingCache;
    private final InFlightCoalescer coalescer;
    private final CacheProperties properties;
    private final MetricsRecorder metrics;

    public CompletionService(GatewayService gateway, LlmClient client, RouterService router,
                             SemanticCache cache, EmbeddingCache embeddingCache,
                             InFlightCoalescer coalescer, CacheProperties properties,
                             MetricsRecorder metrics) {
        this.gateway = gateway;
        this.client = client;
        this.router = router;
        this.cache = cache;
        this.embeddingCache = embeddingCache;
        this.coalescer = coalescer;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Mono<CompletionResult> complete(ChatRequest request, ApiKeyIdentity identity) {
        if (!cacheUsable(request)) {
            return upstreamOnly(request, identity);
        }
        String namespace = namespaceOf(request, identity);
        if (!properties.isCoalescingEnabled()) {
            return cacheFirst(request, identity, namespace);
        }
        String key = namespace + '|' + EmbeddingCache.hash(cacheableText(request));
        return coalescer.coalesce(key,
                () -> cacheFirst(request, identity, namespace),
                () -> metrics.recordCoalesced(request.model()));
    }

    // ------------------------------------------------------------------ cache path

    /** A probe outcome: the vector to store with a later answer, plus the lookup result. */
    private record Probe(double[] vector, CacheLookup lookup) {
        boolean hit() {
            return lookup.hit();
        }
    }

    private Mono<CompletionResult> cacheFirst(ChatRequest request, ApiKeyIdentity identity,
                                              String namespace) {
        return probe(request, namespace)
                .flatMap(probe -> probe.hit()
                        ? Mono.just(cachedResult(probe.lookup(), request))
                        : upstream(request, identity, namespace, probe.vector()));
    }

    private Mono<Probe> probe(ChatRequest request, String namespace) {
        String text = cacheableText(request);
        double[] known = embeddingCache.get(text).orElse(null);
        if (known != null) {
            return cache.find(namespace, known, properties.getSimilarityThreshold())
                    .map(lookup -> new Probe(known, lookup));
        }
        Deployment embeddingDeployment = router.deploymentByName(properties.getEmbeddingDeployment());
        if (embeddingDeployment == null) {
            metrics.recordCacheDegraded();
            return Mono.just(new Probe(null, CacheLookup.miss()));
        }
        return client.embed(embeddingDeployment, text)
                .flatMap(vector -> {
                    embeddingCache.put(text, vector);
                    return cache.find(namespace, vector, properties.getSimilarityThreshold())
                            .map(lookup -> new Probe(vector, lookup));
                })
                // a broken cache must not break the product: degrade to the upstream path
                .onErrorResume(e -> {
                    log.warn("semantic cache degraded for {}: {}", request.model(), e.toString());
                    metrics.recordCacheDegraded();
                    return Mono.just(new Probe(null, CacheLookup.miss()));
                });
    }

    private CompletionResult cachedResult(CacheLookup lookup, ChatRequest request) {
        ChatResponse response = Json.read(lookup.payload(), ChatResponse.class);
        int savedPrompt = response.usage() == null || response.usage().promptTokens() == null
                ? 0 : response.usage().promptTokens();
        int savedCompletion = response.usage() == null || response.usage().completionTokens() == null
                ? 0 : response.usage().completionTokens();
        metrics.recordCacheHit(request.model(), lookup.similarity(), savedPrompt, savedCompletion,
                CostCalculator.costUsd(response.model(), response.usage()));
        return CompletionResult.fromCache(response, lookup.similarity());
    }

    private void store(String namespace, double[] vector, ChatResponse response) {
        cache.put(namespace, UUID.randomUUID().toString(), vector, Json.write(response))
                .subscribe(null, e -> log.warn("cache store failed: {}", e.toString()));
    }

    // ------------------------------------------------------------------ upstream path

    private Mono<CompletionResult> upstreamOnly(ChatRequest request, ApiKeyIdentity identity) {
        return gateway.chat(request)
                .map(routed -> CompletionResult.fromUpstream(
                        routed.value(), routed.deployment(), routed.attempted()));
    }

    private Mono<CompletionResult> upstream(ChatRequest request, ApiKeyIdentity identity,
                                            String namespace, double[] vector) {
        metrics.recordCacheMiss(request.model());
        return gateway.chat(request)
                .map(routed -> {
                    ChatResponse response = routed.value();
                    if (vector != null) {
                        store(namespace, vector, response);
                    }
                    return CompletionResult.fromUpstream(
                            response, routed.deployment(), routed.attempted());
                });
    }

    // ------------------------------------------------------------------ keys

    private boolean cacheUsable(ChatRequest request) {
        return properties.isEnabled()
                && !Boolean.TRUE.equals(request.stream())
                && !cacheableText(request).isBlank();
    }

    /**
     * Isolation boundary. The key id is part of it on purpose: serving one caller's answer to
     * another caller is a data leak, not a cache win. Request shape (temperature, limits) is
     * folded in too, since the same prompt at a different temperature is a different question.
     */
    private String namespaceOf(ChatRequest request, ApiKeyIdentity identity) {
        String shape = request.temperature() + "|" + request.maxTokens() + "|" + request.topP();
        return "sc:" + identity.id() + ":" + request.model() + ":"
                + EmbeddingCache.hash(shape).substring(0, 8);
    }

    /** What actually gets embedded: the whole conversation, not just the last message. */
    private static String cacheableText(ChatRequest request) {
        return request.messages().stream()
                .map(m -> m.role() + ":" + (m.content() == null ? "" : m.content()))
                .collect(Collectors.joining("\n"));
    }
}
