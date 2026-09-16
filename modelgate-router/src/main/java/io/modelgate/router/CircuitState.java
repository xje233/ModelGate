package io.modelgate.router;

/**
 * Circuit breaker states of one deployment.
 *
 * <p>CLOSED --(too many failures)--&gt; OPEN --(cooldown elapsed)--&gt; HALF_OPEN
 * --(probe succeeds)--&gt; CLOSED / --(probe fails)--&gt; OPEN
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
