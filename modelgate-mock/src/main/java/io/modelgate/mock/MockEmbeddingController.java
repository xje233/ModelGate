package io.modelgate.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import io.modelgate.core.EmbeddingData;
import io.modelgate.core.EmbeddingRequest;
import io.modelgate.core.EmbeddingResponse;
import io.modelgate.core.Usage;

/**
 * OpenAI-compatible mock embeddings endpoint.
 *
 * <p>The vector is a deterministic hashed bag of <b>character bigrams</b> (plus whole tokens),
 * L2-normalized. It is not a semantic model, but it behaves like one for gateway purposes:
 * identical text scores 1.0, small edits score very high, unrelated text scores low — which is
 * exactly what the semantic cache needs to be exercised. Swap in a real embeddings provider by
 * pointing the embedding deployment at it; the gateway side is unchanged.
 */
@RestController
public class MockEmbeddingController {

    private static final int DIM = 64;

    private final MockProperties props;

    public MockEmbeddingController(MockProperties props) {
        this.props = props;
    }

    @PostMapping("/v1/embeddings")
    public Mono<EmbeddingResponse> embeddings(@RequestBody EmbeddingRequest request) {
        // a small jitter so the cache's latency benefit is visible end to end
        long delay = Math.max(1, props.getP50Ms() / 4 + ThreadLocalRandom.current().nextLong(5));
        return Mono.delay(Duration.ofMillis(delay))
                .map(ignored -> new EmbeddingResponse(
                        "list",
                        request.model(),
                        List.of(new EmbeddingData(0, embed(request.input()))),
                        Usage.of(estimateTokens(request.input()), 0)));
    }

    static double[] embed(String text) {
        double[] vector = new double[DIM];
        String lower = text == null ? "" : text.toLowerCase();
        String dense = lower.replaceAll("\\s+", "");

        // character bigrams — works for both space-delimited and CJK text
        for (int i = 0; i + 1 < dense.length(); i++) {
            vector[Math.floorMod(dense.substring(i, i + 2).hashCode(), DIM)] += 1.0;
        }
        // whole tokens add a coarser signal
        for (String token : lower.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 1) {
                vector[Math.floorMod(token.hashCode(), DIM)] += 0.5;
            }
        }

        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
