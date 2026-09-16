package io.modelgate.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.modelgate.core.ChatCompletionChunk;
import io.modelgate.core.ChatRequest;
import io.modelgate.core.ChatResponse;
import io.modelgate.core.ChunkChoice;
import io.modelgate.core.ChunkDelta;
import io.modelgate.core.Choice;
import io.modelgate.core.Message;
import io.modelgate.core.Usage;

/**
 * OpenAI-compatible mock upstream.
 *
 * <p>Controls (yaml baseline, header overrides per request):
 * <ul>
 *   <li>{@code x-mock-status} — force an HTTP status, e.g. 500 to demo failover</li>
 *   <li>{@code x-mock-delay-ms} — override latency</li>
 *   <li>{@code x-mock-error-rate} — 0..1 random failure injection</li>
 * </ul>
 */
@RestController
public class MockChatController {

    private final MockProperties props;
    private final ObjectMapper mapper;

    public MockChatController(MockProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<Void> chat(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        Integer forceStatus = intHeader(exchange, "x-mock-status");
        boolean streaming = Boolean.TRUE.equals(request.stream());
        long delay = longHeader(exchange, "x-mock-delay-ms",
                streaming ? props.getTtftMs() : props.getP50Ms());
        double errorRate = doubleHeader(exchange, "x-mock-error-rate", props.getErrorRate());

        if (forceStatus != null) {
            return error(response, forceStatus);
        }
        if (ThreadLocalRandom.current().nextDouble() < errorRate) {
            return error(response, 503);
        }
        if (streaming) {
            return stream(request, response, delay);
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = write(response(request)).getBytes(StandardCharsets.UTF_8);
        return Mono.delay(Duration.ofMillis(delay))
                .flatMap(ignored -> response.writeWith(
                        Mono.just(response.bufferFactory().wrap(body))));
    }

    private Mono<Void> error(ServerHttpResponse response, int status) {
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":{\"message\":\"mock injected status " + status
                + "\",\"type\":\"mock_error\"}}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private Mono<Void> stream(ChatRequest request, ServerHttpResponse response, long ttft) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        String reply = composeReply(request);
        int pieces = Math.max(1, props.getReplyChunks());
        String id = "chatcmpl-mock-" + UUID.randomUUID().toString().substring(0, 8);
        long created = System.currentTimeMillis() / 1000;

        List<String> events = new ArrayList<>(pieces + 2);
        events.add(write(new ChatCompletionChunk(id, "chat.completion.chunk", created,
                request.model(),
                List.of(new ChunkChoice(0, new ChunkDelta("assistant", ""), null)), null)));
        for (String piece : split(reply, pieces)) {
            events.add(write(new ChatCompletionChunk(id, "chat.completion.chunk", created,
                    request.model(),
                    List.of(new ChunkChoice(0, new ChunkDelta(null, piece), null)), null)));
        }
        int promptTokens = estimateTokens(request);
        int completionTokens = estimateTokens(reply);
        events.add(write(new ChatCompletionChunk(id, "chat.completion.chunk", created,
                request.model(),
                List.of(new ChunkChoice(0, new ChunkDelta(null, null), "stop")),
                Usage.of(promptTokens, completionTokens))));

        // pacing: TTFT then one chunk per interval; flush per frame; [DONE] terminates
        Flux<String> frames = Flux.interval(Duration.ofMillis(ttft),
                        Duration.ofMillis(props.getChunkIntervalMs()))
                .take(events.size())
                .map(i -> "data:" + events.get(i.intValue()) + "\n\n")
                .concatWith(Mono.just("data:[DONE]\n\n"));
        return response.writeAndFlushWith(frames
                .map(text -> Mono.just(response.bufferFactory()
                        .wrap(text.getBytes(StandardCharsets.UTF_8)))));
    }

    private ChatResponse response(ChatRequest request) {
        String reply = composeReply(request);
        return new ChatResponse(
                "chatcmpl-mock-" + UUID.randomUUID().toString().substring(0, 8),
                "chat.completion",
                System.currentTimeMillis() / 1000,
                request.model(),
                List.of(new Choice(0, new Message("assistant", reply), "stop")),
                Usage.of(estimateTokens(request), estimateTokens(reply)));
    }

    private String composeReply(ChatRequest request) {
        String last = request.lastUserContent();
        if (last.length() > 40) {
            last = last.substring(0, 40) + "...";
        }
        return "hello from upstream [" + props.getName() + "] model=" + request.model()
                + ". you said: " + last;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> split(String text, int pieces) {
        List<String> result = new ArrayList<>(pieces);
        int len = Math.max(1, text.length() / pieces);
        for (int i = 0; i < text.length(); i += len) {
            result.add(text.substring(i, Math.min(text.length(), i + len)));
        }
        return result;
    }

    private static int estimateTokens(ChatRequest request) {
        int chars = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length())
                .sum();
        return Math.max(1, chars / 4);
    }

    private static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private static Integer intHeader(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longHeader(ServerWebExchange exchange, String name, long fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleHeader(ServerWebExchange exchange, String name, double fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
