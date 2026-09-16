package io.modelgate.router;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-deployment circuit breaker.
 *
 * <p>Trips on either signal, because either one alone is easy to fool:
 * <ul>
 *   <li><b>consecutive failures</b> ≥ {@code allowedFails} — catches a hard outage fast</li>
 *   <li><b>failure rate</b> ≥ {@code failureRateThreshold} over the last {@code windowSize}
 *       calls (with at least {@code minCallsForRate} samples) — catches a flaky upstream that
 *       never fails twice in a row</li>
 * </ul>
 *
 * <p>Recovery: OPEN for {@code cooldownMillis} (doubling on each consecutive trip up to a cap),
 * then HALF_OPEN with a single probe permit. A successful probe closes the breaker; a failed
 * one re-opens it with the next backoff step.
 *
 * <p>Single-instance only: with several gateway replicas each keeps its own view. The interface
 * exists so a Redis-backed implementation can replace it without touching the router.
 */
public final class InProcessRouterState implements RouterState {

    private static final long PROBE_STALE_MILLIS = 10_000;

    private static final class Breaker {
        volatile CircuitState state = CircuitState.CLOSED;
        volatile long openUntil;
        volatile long probeStartedAt;
        volatile int consecutiveFails;
        /** monotonic — feeds the metrics counter, never reset */
        volatile long trips;
        /** consecutive trips since the last recovery — drives the cooldown backoff */
        volatile int backoffStep;
        final Deque<Boolean> recent = new ArrayDeque<>();
    }

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final int allowedFails;
    private final long cooldownMillis;
    private final int windowSize;
    private final double failureRateThreshold;
    private final int minCallsForRate;
    private final long maxCooldownMillis;
    private final LongSupplier clock;

    public InProcessRouterState(int allowedFails, long cooldownSeconds) {
        this(allowedFails, cooldownSeconds, 20, 0.5, 10, System::currentTimeMillis);
    }

    public InProcessRouterState(int allowedFails, long cooldownSeconds, int windowSize,
                                double failureRateThreshold, int minCallsForRate) {
        this(allowedFails, cooldownSeconds, windowSize, failureRateThreshold, minCallsForRate,
                System::currentTimeMillis);
    }

    public InProcessRouterState(int allowedFails, long cooldownSeconds, int windowSize,
                                double failureRateThreshold, int minCallsForRate,
                                LongSupplier clock) {
        this.allowedFails = Math.max(1, allowedFails);
        this.cooldownMillis = Math.max(1, cooldownSeconds) * 1000L;
        this.windowSize = Math.max(2, windowSize);
        this.failureRateThreshold = failureRateThreshold;
        this.minCallsForRate = Math.max(1, minCallsForRate);
        this.maxCooldownMillis = this.cooldownMillis * 10;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable(String key) {
        Breaker breaker = breakers.get(key);
        if (breaker == null) {
            return true;
        }
        long now = clock.getAsLong();
        if (breaker.state == CircuitState.CLOSED) {
            return true;
        }
        if (breaker.state == CircuitState.OPEN) {
            if (now < breaker.openUntil) {
                return false;
            }
            synchronized (breaker) {
                if (breaker.state == CircuitState.OPEN && now >= breaker.openUntil) {
                    breaker.state = CircuitState.HALF_OPEN;
                    breaker.probeStartedAt = 0;
                }
            }
        }
        // HALF_OPEN: exactly one probe at a time, with an escape hatch if a permit is never used
        synchronized (breaker) {
            if (breaker.state != CircuitState.HALF_OPEN) {
                return true;
            }
            boolean probeFree = breaker.probeStartedAt == 0
                    || now - breaker.probeStartedAt > PROBE_STALE_MILLIS;
            if (probeFree) {
                breaker.probeStartedAt = now;
                return true;
            }
            return false;
        }
    }

    @Override
    public CircuitState stateOf(String key) {
        Breaker breaker = breakers.get(key);
        return breaker == null ? CircuitState.CLOSED : breaker.state;
    }

    @Override
    public long openCount(String key) {
        Breaker breaker = breakers.get(key);
        return breaker == null ? 0 : breaker.trips;
    }

    @Override
    public void recordSuccess(String key) {
        Breaker breaker = breakers.get(key);
        if (breaker == null) {
            return;
        }
        long now = clock.getAsLong();
        synchronized (breaker) {
            breaker.consecutiveFails = 0;
            breaker.probeStartedAt = 0;
            if (breaker.state != CircuitState.CLOSED) {
                // recovery: start from a clean slate so the next outage gets a fresh backoff
                breaker.state = CircuitState.CLOSED;
                breaker.openUntil = 0;
                breaker.backoffStep = 0;
                breaker.recent.clear();
                return;
            }
            append(breaker, Boolean.FALSE);
        }
    }

    @Override
    public void recordFailure(String key) {
        Breaker breaker = breakers.computeIfAbsent(key, k -> new Breaker());
        long now = clock.getAsLong();
        synchronized (breaker) {
            if (breaker.state == CircuitState.OPEN && now < breaker.openUntil) {
                return; // already out of rotation
            }
            append(breaker, Boolean.TRUE);
            boolean rateTripped = breaker.recent.size() >= minCallsForRate
                    && failureRate(breaker) >= failureRateThreshold;
            boolean consecutiveTripped = breaker.consecutiveFails + 1 >= allowedFails;
            breaker.consecutiveFails++;

            if (rateTripped || consecutiveTripped || breaker.state == CircuitState.HALF_OPEN) {
                open(breaker, now);
            }
        }
    }

    private void append(Breaker breaker, Boolean outcome) {
        breaker.recent.addLast(outcome);
        while (breaker.recent.size() > windowSize) {
            breaker.recent.removeFirst();
        }
    }

    private void open(Breaker breaker, long now) {
        breaker.trips++;
        breaker.backoffStep++;
        long backoff = Math.min(maxCooldownMillis, cooldownMillis * Math.max(1, breaker.backoffStep));
        breaker.state = CircuitState.OPEN;
        breaker.openUntil = now + backoff;
        breaker.consecutiveFails = 0;
        breaker.probeStartedAt = 0;
        breaker.recent.clear();
    }

    private double failureRate(Breaker breaker) {
        long failures = breaker.recent.stream().filter(Boolean::booleanValue).count();
        return failures / (double) breaker.recent.size();
    }

    /** Visible for tests. */
    public void reset() {
        breakers.clear();
    }
}
