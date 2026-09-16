package io.modelgate.providers;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Deployment;
import io.modelgate.core.EmbeddingRequest;

/**
 * SPI of an upstream provider. A provider knows how to translate the canonical
 * requests into concrete HTTP requests for its wire format.
 *
 * <p>Adding a provider must never require touching core/router/proxy: register an
 * implementation and reference it by name in the deployment config.
 */
public interface Provider {

    /** Provider name referenced by {@link Deployment#provider()}. */
    String name();

    /** Build the upstream chat completion request for the given deployment. */
    ProviderHttpRequest build(Deployment deployment, ChatRequest request);

    /** Build the upstream embeddings request for the given deployment. */
    ProviderHttpRequest buildEmbeddings(Deployment deployment, EmbeddingRequest request);
}
