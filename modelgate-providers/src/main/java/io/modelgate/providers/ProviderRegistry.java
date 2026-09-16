package io.modelgate.providers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Deployment;

/**
 * Registry of available providers. Unknown names fall back to the generic
 * OpenAI-compatible provider, which covers any endpoint speaking the OpenAI wire format
 * (DeepSeek, Kimi/Moonshot, DashScope compatible-mode, vLLM, ...).
 */
public final class ProviderRegistry {

    private final Map<String, Provider> providers;
    private final Provider defaultProvider;

    public ProviderRegistry(Map<String, Provider> providers, Provider defaultProvider) {
        this.providers = Map.copyOf(providers);
        this.defaultProvider = defaultProvider;
    }

    public static ProviderRegistry ofDefaults() {
        Map<String, Provider> map = new LinkedHashMap<>();
        for (String name : List.of("openai", "deepseek", "kimi", "moonshot", "qwen", "zhipu", "mock")) {
            map.put(name, new OpenAiProvider(name));
        }
        return new ProviderRegistry(map, new OpenAiProvider("openai-compatible"));
    }

    public Provider get(String name) {
        Provider p = providers.get(name);
        return p != null ? p : defaultProvider;
    }

    /** Convenience for building requests without a full Spring context (tests, CLI). */
    public ProviderHttpRequest build(Deployment deployment, ChatRequest request) {
        return get(deployment.provider()).build(deployment, request);
    }
}
