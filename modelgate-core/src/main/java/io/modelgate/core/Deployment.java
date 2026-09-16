package io.modelgate.core;

import java.util.Map;

/**
 * One upstream target of the router. A model group (the public alias) maps to a list of
 * deployments; each deployment is a concrete provider + model + endpoint.
 *
 * <p>Lives in core so that the provider layer (below) and the router/client layers (above)
 * can share the type without inverting the dependency direction.
 */
public record Deployment(
        String name,
        String group,
        String provider,
        String modelId,
        String baseUrl,
        String apiKey,
        int weight,
        Map<String, String> headers) {

    public Deployment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("deployment name is required");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("deployment group is required");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("deployment modelId is required");
        }
        if (weight <= 0) {
            weight = 1;
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Cooldown key: unique across the fleet. */
    public String key() {
        return name;
    }
}
