package io.modelgate.providers;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Deployment;
import io.modelgate.core.EmbeddingRequest;
import io.modelgate.providers.json.Json;

/**
 * Generic OpenAI-wire-format provider. Handles every endpoint that speaks the OpenAI
 * protocol (chat completions + embeddings); per-deployment differences (base url, api key,
 * extra headers) are carried by the {@link Deployment} itself.
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
        ChatRequest upstreamRequest = request.withModel(deployment.modelId());
        Map<String, String> headers = headers(deployment,
                Boolean.TRUE.equals(request.stream()) ? "text/event-stream" : "application/json");
        return new ProviderHttpRequest(
                endpoint(deployment, "/v1/chat/completions"), headers, Json.write(upstreamRequest));
    }

    @Override
    public ProviderHttpRequest buildEmbeddings(Deployment deployment, EmbeddingRequest request) {
        EmbeddingRequest upstreamRequest = new EmbeddingRequest(deployment.modelId(), request.input());
        return new ProviderHttpRequest(
                endpoint(deployment, "/v1/embeddings"),
                headers(deployment, "application/json"),
                Json.write(upstreamRequest));
    }

    private static String endpoint(Deployment deployment, String path) {
        String base = deployment.baseUrl() == null ? "" : deployment.baseUrl().replaceAll("/+$", "");
        return base + path;
    }

    private static Map<String, String> headers(Deployment deployment, String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", accept);
        String apiKey = deployment.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.putAll(deployment.headers());
        return headers;
    }
}
