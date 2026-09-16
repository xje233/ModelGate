package io.modelgate.core;

/** One embedding vector of an embeddings response. */
public record EmbeddingData(Integer index, double[] embedding) {
}
