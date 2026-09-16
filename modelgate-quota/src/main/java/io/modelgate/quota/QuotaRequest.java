package io.modelgate.quota;

import java.util.List;

/**
 * One quota check: three subjects (key / tenant / model) and their limits.
 * Limits are resolved by the caller (from config now, from MySQL later) so the limiter
 * itself stays a pure counter.
 */
public record QuotaRequest(
        String keyId,
        String tenantId,
        String model,
        QuotaLimits keyLimits,
        QuotaLimits tenantLimits,
        QuotaLimits modelLimits) {

    public QuotaLimits limitsOf(QuotaScope scope) {
        return switch (scope) {
            case KEY -> keyLimits;
            case TENANT -> tenantLimits;
            case MODEL -> modelLimits;
        };
    }

    /** Subject of a dimension, e.g. the key id / tenant id / model alias. */
    public String subjectOf(QuotaScope scope) {
        return switch (scope) {
            case KEY -> keyId;
            case TENANT -> tenantId;
            case MODEL -> model;
        };
    }

    /** Redis key of the request counter for one dimension. */
    public String rpmKeyOf(QuotaScope scope) {
        return QuotaKeys.rpm(scope, subjectOf(scope));
    }

    /** Redis key of the token counter for one dimension. */
    public String tpmKeyOf(QuotaScope scope) {
        return QuotaKeys.tpm(scope, subjectOf(scope));
    }

    public List<QuotaScope> scopes() {
        return List.of(QuotaScope.KEY, QuotaScope.TENANT, QuotaScope.MODEL);
    }
}
