package io.modelgate.router;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * In-process cooldown state: consecutive-failure counter per deployment; reaching
 * {@code allowedFails} puts the deployment into cooldown for {@code cooldownSeconds}.
 * Cooldown expiry acts as the HALF-OPEN probe (next request is the trial request).
 *
 * <p>Single-instance only. With multiple gateway replicas the counters diverge — the
 * Redis-backed implementation is the cluster answer (see plan.md, pitfall #7).
 */
public final class InProcessRouterState implements RouterState {

    private static final class Counter {
        final AtomicInteger consecutiveFails = new AtomicInteger();
        volatile long cooldownUntil;
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final int allowedFails;
    private final long cooldownMs;
    private final LongSupplier clock;

    public InProcessRouterState(int allowedFails, long cooldownSeconds) {
        this(allowedFails, cooldownSeconds, System::currentTimeMillis);
    }

    public InProcessRouterState(int allowedFails, long cooldownSeconds, LongSupplier clock) {
        this.allowedFails = Math.max(1, allowedFails);
        this.cooldownMs = cooldownSeconds * 1000L;
        this.clock = clock;
    }

    @Override
    public boolean isCoolingDown(String key) {
        Counter c = counters.get(key);
        if (c == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (now < c.cooldownUntil) {
            return true;
        }
        // expired -> HALF-OPEN: allow the next request as the recovery probe
        if (c.cooldownUntil > 0) {
            c.cooldownUntil = 0;
            c.consecutiveFails.set(0);
        }
        return false;
    }

    @Override
    public void recordSuccess(String key) {
        Counter c = counters.get(key);
        if (c != null) {
            c.consecutiveFails.set(0);
            c.cooldownUntil = 0;
        }
    }

    @Override
    public void recordFailure(String key) {
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        if (c.cooldownUntil > 0 && clock.getAsLong() < c.cooldownUntil) {
            return; // already cooling down
        }
        if (c.consecutiveFails.incrementAndGet() >= allowedFails) {
            c.cooldownUntil = clock.getAsLong() + cooldownMs;
            c.consecutiveFails.set(0);
        }
    }
}
