package io.modelgate.proxy.config;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import io.modelgate.proxy.service.QuotaGuard;
import io.modelgate.quota.InProcessQuotaLimiter;
import io.modelgate.quota.QuotaLimiter;
import io.modelgate.quota.RedisQuotaLimiter;

/**
 * Picks the quota backend. {@code memory} keeps the gateway dependency-free for local runs;
 * {@code redis} shares counters across replicas and runs all three dimensions in one Lua call.
 */
@Configuration
public class QuotaConfig {

    @Bean
    public QuotaLimiter quotaLimiter(QuotaProperties properties,
                                     ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        Duration window = Duration.ofSeconds(properties.getWindowSeconds());
        if ("redis".equalsIgnoreCase(properties.getBackend())) {
            ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException(
                        "modelgate.quota.backend=redis requires spring.data.redis.* to be configured");
            }
            return new RedisQuotaLimiter(redis, window);
        }
        return new InProcessQuotaLimiter(window);
    }

    @Bean
    public QuotaGuard quotaGuard(QuotaLimiter limiter, QuotaProperties properties,
                                 MeterRegistry meterRegistry) {
        return new QuotaGuard(limiter, properties, meterRegistry);
    }
}
