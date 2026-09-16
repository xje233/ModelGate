package io.modelgate.quota;

import reactor.core.publisher.Mono;

/**
 * Multi-dimensional quota enforcement.
 *
 * <p>Split into two phases because tokens are only known after the upstream answered:
 * {@link #acquire} runs pre-call (request window + already-consumed tokens),
 * {@link #chargeTokens} runs post-call (actual usage). RPM is consumed at acquire time,
 * TPM at charge time.
 */
public interface QuotaLimiter {

    /**
     * Pre-call check across all three dimensions. On success the request counter is consumed
     * for every dimension.
     *
     * @param requestId unique id of this request, used as the sliding-window member
     */
    Mono<QuotaDecision> acquire(QuotaRequest request, String requestId);

    /** Post-call token accounting for every dimension. */
    Mono<Void> chargeTokens(QuotaRequest request, int promptTokens, int completionTokens);
}
