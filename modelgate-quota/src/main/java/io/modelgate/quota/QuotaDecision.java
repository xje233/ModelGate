package io.modelgate.quota;

/**
 * Outcome of a quota check. {@code retryAfterSeconds} is what the gateway puts into the
 * HTTP {@code Retry-After} header on a 429.
 */
public record QuotaDecision(
        boolean allowed,
        QuotaScope rejectedScope,
        String rejectedSubject,
        long retryAfterSeconds,
        long remainingRequests) {

    public static QuotaDecision allow(long remainingRequests) {
        return new QuotaDecision(true, null, null, 0, remainingRequests);
    }

    public static QuotaDecision reject(QuotaScope scope, String subject, long retryAfterMillis) {
        long seconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        return new QuotaDecision(false, scope, subject, seconds, 0);
    }
}
