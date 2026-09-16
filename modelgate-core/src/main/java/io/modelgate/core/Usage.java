package io.modelgate.core;

/** Token usage of one completion, OpenAI canonical format. */
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
