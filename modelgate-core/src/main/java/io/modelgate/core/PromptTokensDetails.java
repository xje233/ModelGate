package io.modelgate.core;

/**
 * Breakdown of the prompt tokens, OpenAI canonical format
 * ({@code usage.prompt_tokens_details}).
 *
 * <p>Needed because prompt caching is billed differently from fresh input:
 * a cache <b>hit</b> is cheaper and a cache <b>write</b> is more expensive.
 *
 * @param cachedTokens     tokens served from the provider's prompt cache
 * @param cacheWriteTokens tokens written into the provider's prompt cache this call
 */
public record PromptTokensDetails(Integer cachedTokens, Integer cacheWriteTokens) {

    public static final PromptTokensDetails NONE = new PromptTokensDetails(0, 0);

    public int cached() {
        return cachedTokens == null ? 0 : cachedTokens;
    }

    public int cacheWrite() {
        return cacheWriteTokens == null ? 0 : cacheWriteTokens;
    }
}
