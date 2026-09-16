package io.modelgate.proxy.security;

import java.util.Set;

import io.modelgate.quota.QuotaLimits;

/**
 * Authenticated caller. Carries everything the quota layer needs, so the guard never has
 * to look the key up again.
 */
public record ApiKeyIdentity(
        String id,
        String tenant,
        Set<String> allowedModels,
        QuotaLimits limits) {

    public static final String ATTRIBUTE = "modelgate.apiKeyIdentity";

    /** An empty allow-list or "*" means every model group is reachable. */
    public boolean allowsModel(String model) {
        return allowedModels.isEmpty() || allowedModels.contains("*") || allowedModels.contains(model);
    }
}
