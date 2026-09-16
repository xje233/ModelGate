package io.modelgate.proxy.service;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Usage;
import io.modelgate.proxy.config.QuotaProperties;
import io.modelgate.proxy.security.ApiKeyIdentity;
import io.modelgate.quota.QuotaDecision;
import io.modelgate.quota.QuotaLimiter;
import io.modelgate.quota.QuotaRequest;

/**
 * Runs before routing: model allow-list, then the three quota dimensions
 * (key -> tenant -> model, most specific first). Token accounting happens after the
 * upstream answered, off the request's critical path.
 */
public final class QuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);

    private final QuotaLimiter limiter;
    private final QuotaProperties properties;
    private final MeterRegistry meterRegistry;

    public QuotaGuard(QuotaLimiter limiter, QuotaProperties properties, MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** Successful check plus the resolved quota context, reused later for token charging. */
    public record Guarded(QuotaDecision decision, QuotaRequest quotaRequest) {
    }

    public Mono<Guarded> guard(ChatRequest request, ApiKeyIdentity identity) {
        String model = request.model();
        if (!identity.allowsModel(model)) {
            return Mono.error(new ModelNotAllowedException(model, identity.id()));
        }
        QuotaRequest quotaRequest = new QuotaRequest(
                identity.id(),
                identity.tenant(),
                model,
                identity.limits(),
                properties.limitsForTenant(identity.tenant()),
                properties.limitsForModel(model));

        return limiter.acquire(quotaRequest, UUID.randomUUID().toString())
                .flatMap(decision -> {
                    if (!decision.allowed()) {
                        meterRegistry.counter("modelgate_ratelimit_rejected_total",
                                        "scope", decision.rejectedScope().name().toLowerCase())
                                .increment();
                        return Mono.error(new QuotaExceededException(decision, model));
                    }
                    return Mono.just(new Guarded(decision, quotaRequest));
                });
    }

    /** Fire-and-forget accounting: never let bookkeeping slow down or fail a request. */
    public void chargeUsage(Guarded guarded, Usage usage) {
        if (usage == null) {
            return;
        }
        int prompt = usage.promptTokens() == null ? 0 : usage.promptTokens();
        int completion = usage.completionTokens() == null ? 0 : usage.completionTokens();
        limiter.chargeTokens(guarded.quotaRequest(), prompt, completion)
                .subscribe(null, e -> log.warn("token accounting failed for {}: {}",
                        guarded.quotaRequest().model(), e.toString()));
    }
}
