package io.modelgate.core;

/** Token usage of one completion, OpenAI canonical format. */
public record Usage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        PromptTokensDetails promptTokensDetails) {

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens, null);
    }

    public static Usage of(int promptTokens, int completionTokens, PromptTokensDetails details) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens, details);
    }

    public int prompt() {
        return promptTokens == null ? 0 : promptTokens;
    }

    public int completion() {
        return completionTokens == null ? 0 : completionTokens;
    }

    public int cachedPrompt() {
        return promptTokensDetails == null ? 0 : promptTokensDetails.cached();
    }

    public int cacheWritePrompt() {
        return promptTokensDetails == null ? 0 : promptTokensDetails.cacheWrite();
    }
}
