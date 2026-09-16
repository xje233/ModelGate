package io.modelgate.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class InProcessQuotaLimiterTest {

    /** Deterministic clock so window behaviour is asserted, not slept on. */
    private static final class MutableClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static QuotaRequest request(QuotaLimits key, QuotaLimits tenant, QuotaLimits model) {
        return new QuotaRequest("key-a", "tenant-a", "fast-medium", key, tenant, model);
    }

    private static final QuotaLimits UNLIMITED = QuotaLimits.UNLIMITED;

    @Test
    void rejectsWhenKeyRpmExceeded() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofSeconds(60), clock);
        QuotaRequest request = request(QuotaLimits.of(2, -1), UNLIMITED, UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        assertTrue(limiter.acquire(request, "r2").block().allowed());

        QuotaDecision third = limiter.acquire(request, "r3").block();
        assertFalse(third.allowed());
        assertEquals(QuotaScope.KEY, third.rejectedScope());
        assertEquals("key-a", third.rejectedSubject());
        assertTrue(third.retryAfterSeconds() >= 1);
    }

    @Test
    void rpmWindowSlidesAndRecovers() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofMillis(1000), clock);
        QuotaRequest request = request(QuotaLimits.of(1, -1), UNLIMITED, UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        assertFalse(limiter.acquire(request, "r2").block().allowed());

        clock.advance(1001);
        assertTrue(limiter.acquire(request, "r3").block().allowed());
    }

    @Test
    void mostSpecificDimensionIsCheckedFirst() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofSeconds(60), clock);
        // key still has room, tenant is exhausted -> tenant must be the reported dimension
        QuotaRequest request = request(QuotaLimits.of(100, -1), QuotaLimits.of(1, -1), UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        QuotaDecision second = limiter.acquire(request, "r2").block();
        assertFalse(second.allowed());
        assertEquals(QuotaScope.TENANT, second.rejectedScope());
        assertEquals("tenant-a", second.rejectedSubject());
    }

    @Test
    void modelDimensionIsEnforced() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofSeconds(60), clock);
        QuotaRequest request = request(UNLIMITED, UNLIMITED, QuotaLimits.of(1, -1));

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        QuotaDecision second = limiter.acquire(request, "r2").block();
        assertFalse(second.allowed());
        assertEquals(QuotaScope.MODEL, second.rejectedScope());
        assertEquals("fast-medium", second.rejectedSubject());
    }

    @Test
    void chargedTokensCountTowardsTpm() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofSeconds(60), clock);
        QuotaRequest request = request(QuotaLimits.of(-1, 100), UNLIMITED, UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        limiter.chargeTokens(request, 30, 30).block();
        assertTrue(limiter.acquire(request, "r2").block().allowed());

        limiter.chargeTokens(request, 30, 30).block();
        QuotaDecision third = limiter.acquire(request, "r3").block();
        assertFalse(third.allowed());
        assertEquals(QuotaScope.KEY, third.rejectedScope());
    }

    @Test
    void unlimitedDimensionsNeverReject() {
        MutableClock clock = new MutableClock();
        InProcessQuotaLimiter limiter = new InProcessQuotaLimiter(Duration.ofSeconds(60), clock);
        QuotaRequest request = request(UNLIMITED, UNLIMITED, UNLIMITED);

        for (int i = 0; i < 100; i++) {
            QuotaDecision decision = limiter.acquire(request, "r" + i).block();
            assertTrue(decision.allowed());
            assertNull(decision.rejectedScope());
        }
    }
}
