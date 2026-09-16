package io.modelgate.proxy.security;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import io.modelgate.proxy.config.QuotaProperties;
import io.modelgate.proxy.config.SecurityProperties;
import io.modelgate.quota.QuotaLimits;

/**
 * API key -> identity. W2 keeps keys in configuration; the MySQL-backed store
 * (t_api_key, hashed token) plugs in behind this same lookup.
 */
public final class ApiKeyRegistry {

    private final Map<String, ApiKeyIdentity> byToken = new HashMap<>();

    public ApiKeyRegistry(SecurityProperties security, QuotaProperties quota) {
        for (SecurityProperties.KeyConfig key : security.getKeys()) {
            QuotaLimits limits = key.getRpm() == null && key.getTpm() == null
                    ? quota.getDefaultKey()
                    : new QuotaLimits(
                            key.getRpm() == null ? -1 : key.getRpm(),
                            key.getTpm() == null ? -1L : key.getTpm());
            ApiKeyIdentity identity = new ApiKeyIdentity(
                    key.getId() == null ? key.getValue() : key.getId(),
                    key.getTenant() == null ? "default" : key.getTenant(),
                    new LinkedHashSet<>(key.getModels() == null ? List.of() : key.getModels()),
                    limits);
            byToken.put(key.getValue(), identity);
        }
    }

    /** @return the identity, or {@code null} when the token is unknown */
    public ApiKeyIdentity find(String token) {
        return token == null ? null : byToken.get(token);
    }

    public int size() {
        return byToken.size();
    }
}
