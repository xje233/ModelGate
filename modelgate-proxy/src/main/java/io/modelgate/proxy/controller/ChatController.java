package io.modelgate.proxy.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.modelgate.client.CostCalculator;
import io.modelgate.core.ChatRequest;
import io.modelgate.providers.json.Json;
import io.modelgate.proxy.security.ApiKeyIdentity;
import io.modelgate.proxy.service.GatewayService;
import io.modelgate.proxy.service.QuotaGuard;
import io.modelgate.router.RouterService;

/**
 * OpenAI-compatible public endpoint.
 *
 * <p>Order of work per request: authenticate (filter) -> guard (model allow-list + three
 * quota dimensions) -> route -> stream/return -> asynchronously account the tokens.
 * Both response modes are written manually so encoding never depends on content negotiation
 * and every SSE frame is flushed as soon as it arrives.
 */
@RestController
public class ChatController {

    private final GatewayService gateway;
    private final RouterService router;
    private final QuotaGuard guard;

    public ChatController(GatewayService gateway, RouterService router, QuotaGuard guard) {
        this.gateway = gateway;
        this.router = router;
        this.guard = guard;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<Void> chatCompletions(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        ApiKeyIdentity identity = exchange.getAttribute(ApiKeyIdentity.ATTRIBUTE);
        if (identity == null) {
            return Mono.error(new IllegalStateException("request reached the controller without an identity"));
        }
        ServerHttpResponse response = exchange.getResponse();
        return guard.guard(request, identity)
                .flatMap(guarded -> {
                    setHeader(response, "x-ratelimit-remaining-requests",
                            Long.toString(guarded.decision().remainingRequests()));
                    if (Boolean.TRUE.equals(request.stream())) {
                        return stream(request, response, guarded);
                    }
                    return blocking(request, response, guarded);
                });
    }

    private Mono<Void> stream(ChatRequest request, ServerHttpResponse response,
                             QuotaGuard.Guarded guarded) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        DataBufferFactory buffers = response.bufferFactory();
        AtomicBoolean charged = new AtomicBoolean(false);
        Flux<DataBuffer> frames = gateway.chatStream(request, response)
                .doOnNext(chunk -> {
                    // usage arrives in the final frame; charge once, off the critical path
                    if (chunk.usage() != null && charged.compareAndSet(false, true)) {
                        guard.chargeUsage(guarded, chunk.usage());
                    }
                })
                .map(chunk -> "data:" + Json.write(chunk) + "\n\n")
                .concatWith(Mono.just("data:[DONE]\n\n"))
                .map(text -> buffers.wrap(text.getBytes(StandardCharsets.UTF_8)));
        // flush after every frame — the whole point of token-level streaming
        return response.writeAndFlushWith(frames.map(Mono::just));
    }

    private Mono<Void> blocking(ChatRequest request, ServerHttpResponse response,
                                QuotaGuard.Guarded guarded) {
        return gateway.chat(request).flatMap(routed -> {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            setHeader(response, "x-modelgate-model-id", routed.deployment().modelId());
            setHeader(response, "x-modelgate-attempted", String.join(",", routed.attempted()));
            guard.chargeUsage(guarded, routed.value().usage());
            BigDecimal cost = CostCalculator.costUsd(
                    routed.value().model(), routed.value().usage());
            if (cost.signum() > 0) {
                setHeader(response, "x-modelgate-cost", cost.toPlainString());
            }
            byte[] body = Json.write(routed.value()).getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }

    @GetMapping("/v1/models")
    public Map<String, Object> models() {
        return Map.of(
                "object", "list",
                "data", router.groups().stream()
                        .map(g -> Map.of(
                                "id", g,
                                "object", "model",
                                "owned_by", "modelgate"))
                        .toList());
    }

    private static void setHeader(ServerHttpResponse response, String name, String value) {
        try {
            if (!response.isCommitted()) {
                response.getHeaders().set(name, value);
            }
        } catch (Exception ignored) {
            // non-fatal header race
        }
    }
}
