package io.modelgate.providers;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Deployment;

/**
 * Generic OpenAI-wire-format provider. Handles every endpoint that speaks the OpenAI
 * chat completions protocol; per-deployment differences (base url, api key, extra headers
 * such as mock controls) are carried by the {@link Deployment} itself.
 */
public final class OpenAiProvider implements Provider {

    private final String name;

    public OpenAiProvider(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderHttpRequest build(Deployment deployment, ChatRequest request) {
        String base = deployment.baseUrl() == null ? "" : deployment.baseUrl().replaceAll("/+$", "");
        ChatRequest upstreamRequest = request.withModel(deployment.modelId());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept",
                Boolean.TRUE.equals(request.stream()) ? "text/event-stream" : "application/json");
        String apiKey = deployment.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.putAll(deployment.headers());

        return new ProviderHttpRequest(
                base + "/v1/chat/completions", headers, io.modelgate.providers.json.Json.write(upstreamRequest));
    }
}
