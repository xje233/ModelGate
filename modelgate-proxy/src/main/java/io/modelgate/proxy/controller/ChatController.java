package io.modelgate.proxy.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
import io.modelgate.proxy.service.GatewayService;
import io.modelgate.router.RouterService;

/**
 * OpenAI-compatible public endpoint. The stream branch is driven by the request body's
 * {@code stream} flag; both branches write the response manually so encoding never
 * depends on content negotiation, and every SSE frame is flushed the moment it arrives
 * (writeAndFlushWith = token-level streaming to the client).
 */
@RestController
public class ChatController {

    private final GatewayService gateway;
    private final RouterService router;

    public ChatController(GatewayService gateway, RouterService router) {
        this.gateway = gateway;
        this.router = router;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<Void> chatCompletions(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        if (Boolean.TRUE.equals(request.stream())) {
            return stream(request, response);
        }
        return blocking(request, response);
    }

    private Mono<Void> stream(ChatRequest request, ServerHttpResponse response) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        DataBufferFactory buffers = response.bufferFactory();
        Flux<DataBuffer> frames = gateway.chatStream(request, response)
                .map(chunk -> "data:" + Json.write(chunk) + "\n\n")
                .concatWith(Mono.just("data:[DONE]\n\n"))
                .map(text -> buffers.wrap(text.getBytes(StandardCharsets.UTF_8)));
        // flush after every frame — the whole point of token-level streaming
        return response.writeAndFlushWith(frames.map(Mono::just));
    }

    private Mono<Void> blocking(ChatRequest request, ServerHttpResponse response) {
        return gateway.chat(request).flatMap(routed -> {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            setHeader(response, "x-modelgate-model-id", routed.deployment().modelId());
            setHeader(response, "x-modelgate-attempted", String.join(",", routed.attempted()));
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
