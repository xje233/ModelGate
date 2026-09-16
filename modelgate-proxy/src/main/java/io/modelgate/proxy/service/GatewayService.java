package io.modelgate.proxy.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.modelgate.client.LlmClient;
import io.modelgate.client.UpstreamException;
import io.modelgate.core.ChatCompletionChunk;
import io.modelgate.core.ChatRequest;
import io.modelgate.core.ChatResponse;
import io.modelgate.core.Deployment;
import io.modelgate.router.RouterService;

/**
 * The heart of the data plane. Walks the router's attempt chain
 * (own group first = retry, then fallback groups) and routes every
 * attempt outcome into the circuit state.
 *
 * <p>Streaming failover rule: an upstream may only be retried if no chunk has
 * been emitted yet — once bytes have reached the client, retrying would duplicate
 * output; the error is propagated instead.
 */
public final class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RouterService router;
    private final LlmClient client;

    public GatewayService(RouterService router, LlmClient client) {
        this.router = router;
        this.client = client;
    }

    /** A completed call plus the routing metadata that belongs to it. */
    public record Routed<T>(Deployment deployment, T value, List<String> attempted) {
    }

    public Mono<Routed<ChatResponse>> chat(ChatRequest request) {
        return Mono.defer(() -> {
            List<Deployment> chain = router.chain(request.model());
            if (chain.isEmpty()) {
                return Mono.error(new NoSuchModelException(request.model()));
            }
            CopyOnWriteArrayList<String> attempted = new CopyOnWriteArrayList<>();
            return attemptChat(chain, 0, request, attempted);
        });
    }

    private Mono<Routed<ChatResponse>> attemptChat(List<Deployment> chain, int index,
                                                   ChatRequest request,
                                                   CopyOnWriteArrayList<String> attempted) {
        if (index >= chain.size()) {
            return Mono.error(new UpstreamException(502,
                    "all upstream attempts failed: " + attempted, false));
        }
        Deployment deployment = chain.get(index);
        attempted.add(deployment.key());
        return client.chat(deployment, request)
                .doOnSuccess(v -> router.recordSuccess(deployment))
                .map(v -> new Routed<>(deployment, v, List.copyOf(attempted)))
                .onErrorResume(e -> {
                    if (!(e instanceof UpstreamException ue) || ue.retryable()) {
                        router.recordFailure(deployment);
                    }
                    log.warn("non-stream attempt failed on {}, falling through: {}",
                            deployment.key(), e.getMessage());
                    return attemptChat(chain, index + 1, request, attempted);
                });
    }

    public Flux<ChatCompletionChunk> chatStream(ChatRequest request, ServerHttpResponse response) {
        return Flux.defer(() -> {
            List<Deployment> chain = router.chain(request.model());
            if (chain.isEmpty()) {
                return Flux.error(new NoSuchModelException(request.model()));
            }
            CopyOnWriteArrayList<String> attempted = new CopyOnWriteArrayList<>();
            AtomicBoolean emitted = new AtomicBoolean(false);
            return attemptStream(chain, 0, request, attempted, response, emitted);
        });
    }

    private Flux<ChatCompletionChunk> attemptStream(List<Deployment> chain, int index,
                                                    ChatRequest request,
                                                    CopyOnWriteArrayList<String> attempted,
                                                    ServerHttpResponse response,
                                                    AtomicBoolean emitted) {
        if (index >= chain.size()) {
            return Flux.error(new UpstreamException(502,
                    "all upstream attempts failed: " + attempted, false));
        }
        Deployment deployment = chain.get(index);
        attempted.add(deployment.key());
        setHeader(response, "x-modelgate-model-id", deployment.modelId());
        setHeader(response, "x-modelgate-attempted", String.join(",", attempted));
        AtomicBoolean successMarked = new AtomicBoolean(false);
        return client.chatStream(deployment, request)
                .doOnNext(c -> {
                    emitted.set(true);
                    if (successMarked.compareAndSet(false, true)) {
                        router.recordSuccess(deployment);
                    }
                })
                .onErrorResume(e -> {
                    if (!(e instanceof UpstreamException ue) || ue.retryable()) {
                        router.recordFailure(deployment);
                    }
                    if (emitted.get()) {
                        // bytes already streamed to the client — retrying would duplicate output
                        return Flux.error(e);
                    }
                    log.warn("stream attempt failed on {}, failing over: {}",
                            deployment.key(), e.getMessage());
                    return attemptStream(chain, index + 1, request, attempted, response,
                            emitted);
                });
    }

    /**
     * Headers can only be mutated before the response is committed; for SSE that means
     * "before the first chunk flushes", which is exactly when failover still happens.
     */
    private static void setHeader(ServerHttpResponse response, String name, String value) {
        try {
            if (!response.isCommitted()) {
                response.getHeaders().set(name, value);
            }
        } catch (Exception ignored) {
            // header races with first flush are non-fatal
        }
    }
}
