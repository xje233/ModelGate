package io.modelgate.proxy.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.modelgate.quota.QuotaLimits;

/**
 * modelgate.quota — backend selection, window size, and the tenant/model limit tables.
 * Key limits live on the key definition (see {@link SecurityProperties.KeyConfig}).
 */
@ConfigurationProperties(prefix = "modelgate.quota")
public class QuotaProperties {

    /** memory = single instance; redis = shared counters for a cluster */
    private String backend = "memory";

    private int windowSeconds = 60;

    /** fallback when a key definition does not declare rpm/tpm */
    private QuotaLimits defaultKey = QuotaLimits.UNLIMITED;

    /** fallback for a tenant that has no explicit entry */
    private QuotaLimits defaultTenant = QuotaLimits.UNLIMITED;

    /** fallback for a model group that has no explicit entry */
    private QuotaLimits defaultModel = QuotaLimits.UNLIMITED;

    private Map<String, QuotaLimits> tenants = new LinkedHashMap<>();

    private Map<String, QuotaLimits> models = new LinkedHashMap<>();

    public QuotaLimits limitsForTenant(String tenant) {
        return tenants.getOrDefault(tenant, defaultTenant);
    }

    public QuotaLimits limitsForModel(String model) {
        return models.getOrDefault(model, defaultModel);
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public QuotaLimits getDefaultKey() {
        return defaultKey;
    }

    public void setDefaultKey(QuotaLimits defaultKey) {
        this.defaultKey = defaultKey;
    }

    public QuotaLimits getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(QuotaLimits defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public QuotaLimits getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(QuotaLimits defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, QuotaLimits> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, QuotaLimits> tenants) {
        this.tenants = tenants;
    }

    public Map<String, QuotaLimits> getModels() {
        return models;
    }

    public void setModels(Map<String, QuotaLimits> models) {
        this.models = models;
    }
}
