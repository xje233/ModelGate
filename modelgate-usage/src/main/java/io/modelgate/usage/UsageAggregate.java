package io.modelgate.usage;

import java.math.BigDecimal;

/**
 * One row of a usage report. {@code group} is the value of the aggregation axis
 * (a date, a tenant, a model group or an API key).
 */
public record UsageAggregate(
        String group,
        long requests,
        long promptTokens,
        long completionTokens,
        long cachedPromptTokens,
        long cacheHits,
        BigDecimal costUsd) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public double cacheHitRate() {
        return requests == 0 ? 0 : cacheHits / (double) requests;
    }
}
