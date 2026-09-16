package io.modelgate.quota;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import reactor.core.publisher.Mono;

/**
 * Single-instance limiter. RPM uses a sliding-window log (precise, O(limit) memory);
 * TPM uses a fixed window anchored at the first token (cheap increments).
 *
 * <p>The check-then-consume sequence is guarded by one lock so a burst cannot slip past
 * the limit. With more than one gateway replica the counters diverge — {@link RedisQuotaLimiter}
 * is the cluster answer.
 */
public final class InProcessQuotaLimiter implements QuotaLimiter {

    private static final long DEFAULT_WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    private static final class Window {
        final Deque<Long> rpmTimestamps = new ArrayDeque<>();
        long tpmWindowStart;
        long tpmUsed;
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final long windowMillis;
    private final LongSupplier clock;

    public InProcessQuotaLimiter() {
        this(Duration.ofMinutes(1));
    }

    public InProcessQuotaLimiter(Duration window) {
        this(window, System::currentTimeMillis);
    }

    public InProcessQuotaLimiter(Duration window, LongSupplier clock) {
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    @Override
    public Mono<QuotaDecision> acquire(QuotaRequest request, String requestId) {
        lock.lock();
        try {
            long now = clock.getAsLong();
            for (QuotaScope scope : request.scopes()) {
                QuotaLimits limits = request.limitsOf(scope);
                if (limits.unlimited()) {
                    continue;
                }
                if (!limits.rpmUnlimited()) {
                    Window rpmWindow = window(request.rpmKeyOf(scope));
                    purge(rpmWindow, now);
                    if (rpmWindow.rpmTimestamps.size() >= limits.rpm()) {
                        Long oldest = rpmWindow.rpmTimestamps.peekFirst();
                        long retry = oldest == null ? windowMillis : oldest + windowMillis - now;
                        return Mono.just(QuotaDecision.reject(
                                scope, request.subjectOf(scope), Math.max(1, retry)));
                    }
                }
                if (!limits.tpmUnlimited()) {
                    Window tpmWindow = window(request.tpmKeyOf(scope));
                    rollTpmWindow(tpmWindow, now);
                    if (tpmWindow.tpmUsed >= limits.tpm()) {
                        long retry = tpmWindow.tpmWindowStart + windowMillis - now;
                        return Mono.just(QuotaDecision.reject(
                                scope, request.subjectOf(scope), Math.max(1, retry)));
                    }
                }
            }

            long remaining = 0;
            for (QuotaScope scope : request.scopes()) {
                QuotaLimits limits = request.limitsOf(scope);
                if (!limits.unlimited() && !limits.rpmUnlimited()) {
                    Window window = window(request.rpmKeyOf(scope));
                    window.rpmTimestamps.addLast(now);
                    if (scope == QuotaScope.KEY) {
                        remaining = limits.rpm() - window.rpmTimestamps.size();
                    }
                }
            }
            return Mono.just(QuotaDecision.allow(Math.max(0, remaining)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Mono<Void> chargeTokens(QuotaRequest request, int promptTokens, int completionTokens) {
        int tokens = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (tokens == 0) {
            return Mono.empty();
        }
        lock.lock();
        try {
            long now = clock.getAsLong();
            for (QuotaScope scope : request.scopes()) {
                QuotaLimits limits = request.limitsOf(scope);
                if (limits.tpmUnlimited()) {
                    continue;
                }
                Window window = window(request.tpmKeyOf(scope));
                rollTpmWindow(window, now);
                window.tpmUsed += tokens;
            }
            return Mono.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Visible for tests: drop all counters. */
    public void reset() {
        windows.clear();
    }

    private Window window(String key) {
        return windows.computeIfAbsent(key, k -> new Window());
    }

    private void purge(Window window, long now) {
        long threshold = now - windowMillis;
        while (!window.rpmTimestamps.isEmpty() && window.rpmTimestamps.peekFirst() <= threshold) {
            window.rpmTimestamps.pollFirst();
        }
    }

    private void rollTpmWindow(Window window, long now) {
        if (window.tpmWindowStart == 0 || now - window.tpmWindowStart >= windowMillis) {
            window.tpmWindowStart = now;
            window.tpmUsed = 0;
        }
    }
}
