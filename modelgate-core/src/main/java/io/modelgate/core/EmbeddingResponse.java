package io.modelgate.core;

import java.util.List;

/** Embeddings response, OpenAI canonical format. */
public record EmbeddingResponse(
        String object,
        String model,
        List<EmbeddingData> data,
        Usage usage) {

    public double[] firstVector() {
        if (data == null || data.isEmpty() || data.get(0).embedding() == null) {
            throw new IllegalStateException("embeddings response carried no vector");
        }
        return data.get(0).embedding();
    }
}
