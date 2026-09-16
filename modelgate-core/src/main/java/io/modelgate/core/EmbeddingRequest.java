package io.modelgate.core;

/** Embedding request, OpenAI canonical format. */
public record EmbeddingRequest(String model, String input) {
}
