package io.modelgate.client;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import io.modelgate.core.ChatCompletionChunk;
import io.modelgate.core.ChatRequest;
import io.modelgate.core.ChatResponse;
import io.modelgate.core.Deployment;
import io.modelgate.core.EmbeddingRequest;
import io.modelgate.core.EmbeddingResponse;
import io.modelgate.providers.ProviderHttpRequest;
import io.modelgate.providers.ProviderRegistry;
import io.modelgate.providers.json.Json;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * WebClient-based transport on Reactor Netty. The connection pool is shared across all
 * upstreams ({@code maxConnections}, {@code pendingAcquireTimeout} are the JVM/throughput
 * tuning knobs; see plan.md 7.3).
 *
 * <p>Timeouts are deliberately per-operator instead of a client-wide response timeout:
 * a non-streaming call gets one overall budget, a streaming call gets a gap budget which
 * doubles as the time-to-first-token guard. Both surface as retryable 504s so the router
 * treats a hung upstream exactly like a failing one.
 */
public final class ReactorLlmClient implements LlmClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /** Max gap between two stream signals (TTFT guard + mid-stream idle guard). */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /** Overall budget for a non-streaming call. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final ProviderRegistry providers;
    private final WebClient webClient;

    public ReactorLlmClient(ProviderRegistry providers) {
        this(providers, defaultWebClient());
    }

    public ReactorLlmClient(ProviderRegistry providers, WebClient webClient) {
        this.providers = providers;
        this.webClient = webClient;
    }

    private static WebClient defaultWebClient() {
        ConnectionProvider pool = ConnectionProvider.builder("modelgate-upstream")
                .maxConnections(500)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .build();
        HttpClient http = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .compress(true);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Override
    public Mono<ChatResponse> chat(Deployment deployment, ChatRequest request) {
        ProviderHttpRequest pr = providers.build(deployment, request);
        return webClient.post()
                .uri(URI.create(pr.url()))
                .headers(copy(pr))
                .bodyValue(pr.body())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> Json.read(body, ChatResponse.class));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new UpstreamException(
                                    response.statusCode().value(),
                                    "upstream " + deployment.key() + " -> " + response.statusCode().value()
                                            + ": " + abbreviate(body),
                                    UpstreamException.isRetryableStatus(response.statusCode().value()))));
                })
                .timeout(CALL_TIMEOUT)
                .onErrorMap(TimeoutException.class, timeoutMapper(deployment, CALL_TIMEOUT));
    }

    @Override
    public Flux<ChatCompletionChunk> chatStream(Deployment deployment, ChatRequest request) {
        ProviderHttpRequest pr = providers.build(deployment, request);
        return webClient.post()
                .uri(URI.create(pr.url()))
                .headers(copy(pr))
                .bodyValue(pr.body())
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(SSE_TYPE)
                                .map(ServerSentEvent::data)
                                .takeWhile(data -> data != null && !"[DONE]".equals(data.trim()))
                                .map(data -> Json.read(data, ChatCompletionChunk.class));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMapMany(body -> Flux.error(new UpstreamException(
                                    response.statusCode().value(),
                                    "upstream " + deployment.key() + " -> " + response.statusCode().value()
                                            + ": " + abbreviate(body),
                                    UpstreamException.isRetryableStatus(response.statusCode().value()))));
                })
                .timeout(STREAM_IDLE_TIMEOUT)
                .onErrorMap(TimeoutException.class, timeoutMapper(deployment, STREAM_IDLE_TIMEOUT));
    }

    @Override
    public Mono<double[]> embed(Deployment deployment, String input) {
        ProviderHttpRequest pr = providers.get(deployment.provider())
                .buildEmbeddings(deployment, new EmbeddingRequest(deployment.modelId(), input));
        return webClient.post()
                .uri(URI.create(pr.url()))
                .headers(copy(pr))
                .bodyValue(pr.body())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> Json.read(body, EmbeddingResponse.class).firstVector());
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new UpstreamException(
                                    response.statusCode().value(),
                                    "embeddings " + deployment.key() + " -> "
                                            + response.statusCode().value() + ": " + abbreviate(body),
                                    UpstreamException.isRetryableStatus(response.statusCode().value()))));
                })
                .timeout(CALL_TIMEOUT)
                .onErrorMap(TimeoutException.class, timeoutMapper(deployment, CALL_TIMEOUT));
    }

    private static Function<TimeoutException, Throwable> timeoutMapper(Deployment deployment,
                                                                      Duration budget) {
        return e -> new UpstreamException(504,
                "upstream " + deployment.key() + " exceeded " + budget.toSeconds() + "s", true);
    }

    private static Consumer<HttpHeaders> copy(ProviderHttpRequest pr) {
        return headers -> pr.headers().forEach(headers::set);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
