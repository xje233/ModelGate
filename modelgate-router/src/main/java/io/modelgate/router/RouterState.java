package io.modelgate.router;

/**
 * Pluggable router state (cooldown bookkeeping). Two backends share this interface:
 * in-process (single instance) and Redis (multi instance, W2+).
 */
public interface RouterState {

    boolean isCoolingDown(String deploymentKey);

    void recordSuccess(String deploymentKey);

    void recordFailure(String deploymentKey);
}
