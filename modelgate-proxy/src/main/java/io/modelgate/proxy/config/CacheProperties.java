package io.modelgate.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * modelgate.cache.* — semantic cache policy.
 *
 * <p>{@code similarityThreshold} is the single most consequential knob: 0.95 only catches
 * near-identical prompts (safe), 0.85 starts catching paraphrases but risks serving an
 * answer to a question the user did not quite ask.
 */
@ConfigurationProperties(prefix = "modelgate.cache")
public class CacheProperties {

    private boolean enabled = true;

    /** memory = single instance | redis = shared across replicas */
    private String backend = "memory";

    private double similarityThreshold = 0.95;

    private long ttlSeconds = 1800;

    private int maxEntriesPerNamespace = 200;

    /** deployment name (from modelgate.router) used for embedding calls */
    private String embeddingDeployment;

    /** how many prompt->vector pairs to keep in process */
    private int embeddingCacheEntries = 2000;

    /** collapse concurrent identical requests into one upstream call */
    private boolean coalescingEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxEntriesPerNamespace() {
        return maxEntriesPerNamespace;
    }

    public void setMaxEntriesPerNamespace(int maxEntriesPerNamespace) {
        this.maxEntriesPerNamespace = maxEntriesPerNamespace;
    }

    public String getEmbeddingDeployment() {
        return embeddingDeployment;
    }

    public void setEmbeddingDeployment(String embeddingDeployment) {
        this.embeddingDeployment = embeddingDeployment;
    }

    public int getEmbeddingCacheEntries() {
        return embeddingCacheEntries;
    }

    public void setEmbeddingCacheEntries(int embeddingCacheEntries) {
        this.embeddingCacheEntries = embeddingCacheEntries;
    }

    public boolean isCoalescingEnabled() {
        return coalescingEnabled;
    }

    public void setCoalescingEnabled(boolean coalescingEnabled) {
        this.coalescingEnabled = coalescingEnabled;
    }
}
