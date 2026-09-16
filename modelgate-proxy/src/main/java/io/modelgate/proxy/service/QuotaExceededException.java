package io.modelgate.proxy.service;

import io.modelgate.quota.QuotaDecision;

/** A quota dimension is exhausted; the gateway answers 429 + Retry-After. */
public final class QuotaExceededException extends RuntimeException {

    private final transient QuotaDecision decision;

    public QuotaExceededException(QuotaDecision decision, String model) {
        super("rate limit exceeded on " + decision.rejectedScope().name().toLowerCase()
                + " '" + decision.rejectedSubject() + "' for model '" + model
                + "'; retry after " + decision.retryAfterSeconds() + "s");
        this.decision = decision;
    }

    public QuotaDecision decision() {
        return decision;
    }
}
