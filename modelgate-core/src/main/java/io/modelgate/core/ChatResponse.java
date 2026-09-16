package io.modelgate.core;

import java.util.List;

/** Non-streaming chat completion, OpenAI canonical format. */
public record ChatResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage) {
}
