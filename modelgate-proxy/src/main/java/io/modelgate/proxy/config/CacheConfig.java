package io.modelgate.proxy.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import io.modelgate.cache.EmbeddingCache;
import io.modelgate.cache.InFlightCoalescer;
import io.modelgate.cache.InProcessSemanticCache;
import io.modelgate.cache.RedisSemanticCache;
import io.modelgate.cache.SemanticCache;
import io.modelgate.client.LlmClient;
import io.modelgate.proxy.metrics.MetricsRecorder;
import io.modelgate.proxy.service.CompletionService;
import io.modelgate.proxy.service.GatewayService;
import io.modelgate.proxy.service.UsageCollector;
import io.modelgate.router.RouterService;

/**
 * Semantic cache wiring. {@code memory} keeps the gateway dependency-free for local runs;
 * {@code redis} shares cached answers (and the saved-token accounting) across replicas.
 */
@Configuration
public class CacheConfig {

    @Bean
    public SemanticCache semanticCache(CacheProperties properties,
                                       ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getBackend())) {
            ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException(
                        "modelgate.cache.backend=redis requires spring.data.redis.* to be configured");
            }
            return new RedisSemanticCache(redis,
                    properties.getMaxEntriesPerNamespace(), properties.getTtlSeconds());
        }
        return new InProcessSemanticCache(
                properties.getMaxEntriesPerNamespace(), properties.getTtlSeconds());
    }

    @Bean
    public CompletionService completionService(GatewayService gateway, LlmClient client,
                                               RouterService router, SemanticCache cache,
                                               CacheProperties properties, MetricsRecorder metrics,
                                               UsageCollector usage) {
        return new CompletionService(gateway, client, router, cache,
                new EmbeddingCache(properties.getEmbeddingCacheEntries(), properties.getTtlSeconds()),
                new InFlightCoalescer(), properties, metrics, usage);
    }
}
