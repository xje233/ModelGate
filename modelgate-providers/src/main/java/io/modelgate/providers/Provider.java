package io.modelgate.providers;

import io.modelgate.core.ChatRequest;
import io.modelgate.core.Deployment;

/**
 * SPI of an upstream provider. A provider knows how to translate the canonical
 * {@link ChatRequest} into a concrete HTTP request for its wire format.
 *
 * <p>Adding a provider must never require touching core/router/proxy: register an
 * implementation and reference it by name in the deployment config.
 */
public interface Provider {

    /** Provider name referenced by {@link Deployment#provider()}. */
    String name();

    /** Build the upstream HTTP request for the given deployment. */
    ProviderHttpRequest build(Deployment deployment, ChatRequest request);
}
