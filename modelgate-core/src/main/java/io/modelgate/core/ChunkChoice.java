package io.modelgate.core;

/** One choice of a streamed chunk, OpenAI canonical format. */
public record ChunkChoice(Integer index, ChunkDelta delta, String finishReason) {
}
