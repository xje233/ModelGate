package io.modelgate.core;

import java.util.List;

/** One SSE chunk of a streamed chat completion, OpenAI canonical format. */
public record ChatCompletionChunk(
        String id,
        String object,
        Long created,
        String model,
        List<ChunkChoice> choices,
        Usage usage) {

    public String contentOrNull() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        ChunkDelta delta = choices.get(0).delta();
        return delta == null ? null : delta.content();
    }
}
