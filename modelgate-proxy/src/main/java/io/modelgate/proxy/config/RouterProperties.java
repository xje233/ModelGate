package io.modelgate.proxy.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * modelgate.router.* — group/deployment topology plus circuit parameters.
 * Sourced from yaml now; the same shape moves to t_model (MySQL) with hot reload later.
 */
@ConfigurationProperties(prefix = "modelgate.router")
public class RouterProperties {

    /** group -> deployments (the same group name is the public model alias) */
    private Map<String, GroupConfig> groups = new LinkedHashMap<>();

    /** group -> ordered fallback groups */
    private Map<String, List<String>> fallbacks = new LinkedHashMap<>();

    /** consecutive failures before a deployment is taken out of rotation */
    private int allowedFails = 3;

    /** base cooldown in seconds (doubles on each consecutive trip, capped at 10x) */
    private long cooldownSeconds = 60;

    /** sliding window size for the failure-rate signal */
    private int failureWindowSize = 20;

    /** trip when the failure rate over the window reaches this value */
    private double failureRateThreshold = 0.5;

    /** minimum samples before the failure rate is trusted */
    private int minCallsForRate = 10;

    public static class GroupConfig {
        private List<DeploymentConfig> deployments = new ArrayList<>();

        /** when set, the group runs a canary: this deployment serves the experimental arm */
        private String canaryDeployment;

        /** share of callers (0-100) bucketed into the canary arm */
        private int canaryPercentage;

        public List<DeploymentConfig> getDeployments() {
            return deployments;
        }

        public void setDeployments(List<DeploymentConfig> deployments) {
            this.deployments = deployments;
        }

        public String getCanaryDeployment() {
            return canaryDeployment;
        }

        public void setCanaryDeployment(String canaryDeployment) {
            this.canaryDeployment = canaryDeployment;
        }

        public int getCanaryPercentage() {
            return canaryPercentage;
        }

        public void setCanaryPercentage(int canaryPercentage) {
            this.canaryPercentage = canaryPercentage;
        }
    }

    public static class DeploymentConfig {
        private String name;
        private String provider = "openai";
        private String modelId;
        private String baseUrl;
        private String apiKey;
        private int weight = 10;
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public Map<String, GroupConfig> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, GroupConfig> groups) {
        this.groups = groups;
    }

    public Map<String, List<String>> getFallbacks() {
        return fallbacks;
    }

    public void setFallbacks(Map<String, List<String>> fallbacks) {
        this.fallbacks = fallbacks;
    }

    public int getAllowedFails() {
        return allowedFails;
    }

    public void setAllowedFails(int allowedFails) {
        this.allowedFails = allowedFails;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public int getFailureWindowSize() {
        return failureWindowSize;
    }

    public void setFailureWindowSize(int failureWindowSize) {
        this.failureWindowSize = failureWindowSize;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(double failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getMinCallsForRate() {
        return minCallsForRate;
    }

    public void setMinCallsForRate(int minCallsForRate) {
        this.minCallsForRate = minCallsForRate;
    }
}
