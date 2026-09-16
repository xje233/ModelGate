package io.modelgate.proxy.service;

import java.util.List;

import io.modelgate.core.ChatResponse;
import io.modelgate.core.Deployment;

/**
 * One finished completion plus everything the response headers need to explain how it was
 * produced: which upstream served it, which deployments were tried, and whether it came
 * from the semantic cache.
 */
public record CompletionResult(
        ChatResponse response,
        Deployment deployment,
        List<String> attempted,
        boolean cacheHit,
        double cacheSimilarity) {

    public static CompletionResult fromUpstream(ChatResponse response, Deployment deployment,
                                                List<String> attempted) {
        return new CompletionResult(response, deployment, attempted, false, 0);
    }

    public static CompletionResult fromCache(ChatResponse response, double similarity) {
        return new CompletionResult(response, null, List.of(), true, similarity);
    }
}
