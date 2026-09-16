package io.modelgate.proxy.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelgate.client.CostCalculator;
import io.modelgate.core.Usage;
import io.modelgate.usage.UsageRecord;
import io.modelgate.usage.UsageStore;

/**
 * Turns the outcome of a request into a receipt.
 *
 * <p>Cost is computed here — not by the caller — because only the gateway knows which upstream
 * model actually served the call and whether the prompt cache was hit; and because the vendor
 * cache multipliers differ per provider (see {@link CostCalculator.CachePricing}).
 */
public final class UsageCollector {

    private static final Logger log = LoggerFactory.getLogger(UsageCollector.class);

    private final UsageStore store;
    private final ZoneId zone;

    public UsageCollector(UsageStore store, ZoneId zone) {
        this.store = store;
        this.zone = zone;
    }

    public void record(RequestContext context, String modelGroup, String modelId, String provider,
                       Usage usage, boolean cacheHit, boolean stream, List<String> attempted,
                       String status, long startMillis, long completionStartMillis,
                       long endMillis) {
        try {
            int prompt = usage == null ? 0 : usage.prompt();
            int completion = usage == null ? 0 : usage.completion();
            int cached = usage == null ? 0 : usage.cachedPrompt();
            store.record(new UsageRecord(
                    context.requestId(),
                    context.keyId(),
                    context.tenantId(),
                    modelGroup,
                    modelId,
                    provider,
                    prompt,
                    completion,
                    cached,
                    CostCalculator.costUsd(modelId, usage, CostCalculator.CachePricing.forProvider(provider)),
                    cacheHit,
                    stream,
                    context.arm(),
                    attempted == null ? "" : String.join(",", attempted),
                    status,
                    LocalDate.now(zone),
                    startMillis,
                    completionStartMillis,
                    endMillis));
        } catch (Exception e) {
            // accounting must never break a request that already succeeded
            log.warn("usage record dropped: {}", e.toString());
        }
    }
}
