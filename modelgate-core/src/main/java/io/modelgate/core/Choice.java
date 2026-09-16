package io.modelgate.core;

/** One choice of a non-streaming chat completion. */
public record Choice(Integer index, Message message, String finishReason) {
}
