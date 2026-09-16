package io.modelgate.client;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.modelgate.core.ChatCompletionChunk;
import io.modelgate.core.ChatRequest;
import io.modelgate.core.ChatResponse;
import io.modelgate.core.Deployment;

/** Transport facade: one call against one concrete deployment. Failover lives in the router. */
public interface LlmClient {

    Mono<ChatResponse> chat(Deployment deployment, ChatRequest request);

    Flux<ChatCompletionChunk> chatStream(Deployment deployment, ChatRequest request);
}
