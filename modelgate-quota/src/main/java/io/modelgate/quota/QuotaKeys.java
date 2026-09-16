package io.modelgate.quota;

/**
 * Redis key layout of the quota counters.
 *
 * <p>Single-node friendly. On a Redis Cluster the three dimensions of one request land on
 * different slots, so the single-round-trip Lua script would be rejected there; a cluster
 * deployment would either pin the quota keys to one slot (hash tags per tenant) or accept
 * three round trips.
 */
public final class QuotaKeys {

    private QuotaKeys() {
    }

    public static String rpm(QuotaScope scope, String subject) {
        return "q:" + scope.name().toLowerCase() + ":" + subject + ":rpm";
    }

    public static String tpm(QuotaScope scope, String subject) {
        return "q:" + scope.name().toLowerCase() + ":" + subject + ":tpm";
    }
}
