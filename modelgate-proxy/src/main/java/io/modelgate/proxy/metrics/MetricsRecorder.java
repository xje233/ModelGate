package io.modelgate.proxy.metrics;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.modelgate.core.Deployment;
import io.modelgate.router.RouterService;
import io.modelgate.router.RouterState;

/**
 * All gateway metrics in one place.
 *
 * <p>The metric worth defending in an interview is {@code overhead_latency}: total request time
 * minus upstream time, i.e. what the gateway itself costs. Without it, a P99 number says
 * nothing about whether the gateway or the upstream is the problem.
 */
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;
    private final RouterService router;
    private final RouterState routerState;
    private final AtomicInteger inFlight = new AtomicInteger();

    public MetricsRecorder(MeterRegistry registry, RouterService router, RouterState routerState) {
        this.registry = registry;
        this.router = router;
        this.routerState = routerState;
        registry.gauge("modelgate_in_flight", inFlight);
    }

    /**
     * Circuit state as a gauge plus a monotonic open counter, one series per deployment.
     * The numeric mapping is explicit rather than {@code ordinal()} so renaming or reordering
     * the enum can never silently repaint every dashboard.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bindCircuitMetrics() {
        for (Deployment deployment : router.deployments()) {
            String key = deployment.key();
            Gauge.builder("modelgate_circuit_state", routerState, s -> stateCode(s.stateOf(key)))
                    .tag("deployment", key)
                    .tag("provider", deployment.provider())
                    .strongReference(true)
                    .register(registry);
            FunctionCounter.builder("modelgate_circuit_opened_total", routerState,
                            s -> s.openCount(key))
                    .tag("deployment", key)
                    .tag("provider", deployment.provider())
                    .register(registry);
        }
    }

    /** 0 = CLOSED (healthy), 1 = OPEN (out of rotation), 2 = HALF_OPEN (single probe). */
    private static double stateCode(io.modelgate.router.CircuitState state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }

    public void requestStarted() {
        inFlight.incrementAndGet();
    }

    public void requestFinished() {
        inFlight.decrementAndGet();
    }

    public void recordSuccess(String modelGroup, String provider, boolean stream,
                              long totalNanos, long upstreamNanos) {
        registry.counter("modelgate_requests_total",
                "model_group", modelGroup, "provider", provider,
                "stream", String.valueOf(stream), "status", "success").increment();
        Timer.builder("modelgate_request_latency")
                .tags("model_group", modelGroup, "provider", provider, "stream", String.valueOf(stream))
                .register(registry).record(totalNanos, TimeUnit.NANOSECONDS);
        Timer.builder("modelgate_upstream_latency").tag("provider", provider)
                .register(registry).record(upstreamNanos, TimeUnit.NANOSECONDS);
        long overhead = Math.max(0, totalNanos - upstreamNanos);
        Timer.builder("modelgate_overhead_latency")
                .tags("model_group", modelGroup)
                .register(registry).record(overhead, TimeUnit.NANOSECONDS);
    }

    public void recordFailure(String modelGroup, String provider, boolean stream, String status) {
        registry.counter("modelgate_requests_total",
                "model_group", modelGroup, "provider", provider,
                "stream", String.valueOf(stream), "status", status).increment();
    }

    /** Time to first token — the latency users actually feel on a streaming call. */
    public void recordTtft(String provider, long nanos) {
        Timer.builder("modelgate_ttft").tag("provider", provider)
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordCacheHit(String modelGroup, double similarity,
                               int savedPromptTokens, int savedCompletionTokens, BigDecimal savedCost) {
        registry.counter("modelgate_cache_lookup_total", "model_group", modelGroup).increment();
        registry.counter("modelgate_cache_hit_total", "model_group", modelGroup).increment();
        registry.summary("modelgate_cache_similarity", "model_group", modelGroup).record(similarity);
        int savedTokens = Math.max(0, savedPromptTokens) + Math.max(0, savedCompletionTokens);
        if (savedTokens > 0) {
            registry.counter("modelgate_tokens_saved_total", "model_group", modelGroup)
                    .increment(savedTokens);
        }
        if (savedCost != null && savedCost.signum() > 0) {
            registry.counter("modelgate_cost_saved_usd_total", "model_group", modelGroup)
                    .increment(savedCost.doubleValue());
        }
    }

    public void recordCacheMiss(String modelGroup) {
        registry.counter("modelgate_cache_lookup_total", "model_group", modelGroup).increment();
        registry.counter("modelgate_cache_miss_total", "model_group", modelGroup).increment();
    }

    public void recordCacheDegraded() {
        registry.counter("modelgate_cache_degraded_total").increment();
    }

    /** Requests folded into someone else's in-flight execution. */
    public void recordCoalesced(String modelGroup) {
        registry.counter("modelgate_coalesced_total", "model_group", modelGroup).increment();
    }
}
