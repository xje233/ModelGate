package io.modelgate.usage;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The receipt of one gateway request — everything a bill, a dashboard or a fraud investigation
 * needs, and nothing that would make the table grow without bound (request bodies stay out).
 *
 * @param completionStartMillis time the first token arrived; {@code end - completionStart} is
 *                              the generation time, {@code completionStart - start} is TTFT
 * @param arm                   which routing arm served the call (canary / stable / single),
 *                              so an experiment can be attributed from the same table as cost
 */
public record UsageRecord(
        String requestId,
        String keyId,
        String tenantId,
        String modelGroup,
        String modelId,
        String provider,
        int promptTokens,
        int completionTokens,
        int cachedPromptTokens,
        BigDecimal costUsd,
        boolean cacheHit,
        boolean stream,
        String arm,
        String attempted,
        String status,
        LocalDate usageDate,
        long startMillis,
        long completionStartMillis,
        long endMillis) {

    public long latencyMillis() {
        return Math.max(0, endMillis - startMillis);
    }

    public long ttftMillis() {
        return completionStartMillis <= 0 ? 0 : Math.max(0, completionStartMillis - startMillis);
    }
}
