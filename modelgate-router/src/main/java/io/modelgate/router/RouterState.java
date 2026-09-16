package io.modelgate.router;

/**
 * Circuit state of the deployments, pluggable so the router logic stays independent of the
 * storage. In-process is the default; a Redis-backed implementation shares breaker state
 * across replicas the same way the quota module does.
 */
public interface RouterState {

    /**
     * Whether a request may be routed to this deployment right now.
     *
     * <p>Note the deliberate side effect: an expired OPEN breaker becomes HALF_OPEN here and
     * hands out a single probe permit, so recovery is bounded to one real request instead of
     * a thundering herd against a still-broken upstream.</p>
     */
    boolean isAvailable(String deploymentKey);

    CircuitState stateOf(String deploymentKey);

    /** How often this deployment has tripped so far — good alerting signal. */
    long openCount(String deploymentKey);

    void recordSuccess(String deploymentKey);

    void recordFailure(String deploymentKey);
}
