package io.modelgate.core;

/** Delta payload of one streamed chunk. Both fields are null in the first/last frames. */
public record ChunkDelta(String role, String content) {
}
