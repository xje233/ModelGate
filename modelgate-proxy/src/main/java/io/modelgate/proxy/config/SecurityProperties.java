package io.modelgate.proxy.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * modelgate.security.* — static API keys for W1. In W2 they move to t_api_key
 * (hashed) behind the same check.
 */
@ConfigurationProperties(prefix = "modelgate.security")
public class SecurityProperties {

    private List<String> apiKeys = new ArrayList<>();

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }
}
