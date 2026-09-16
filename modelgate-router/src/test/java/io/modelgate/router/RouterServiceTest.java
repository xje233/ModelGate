package io.modelgate.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.modelgate.core.Deployment;

class RouterServiceTest {

    private static final class MutableClock implements java.util.function.LongSupplier {
        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static Deployment dep(String group, String name, int weight) {
        return new Deployment(name, group, "mock", name,
                "http://localhost:9001", null, weight, null);
    }

    private static RouterService router(RouterState state, Deployment... deployments) {
        return new RouterService(List.of(deployments), Map.of(), state);
    }

    @Test
    void weightedDistributionIsRoughlyRespected() {
        RouterService router = new RouterService(
                List.of(dep("g", "a", 70), dep("g", "b", 30)),
                Map.of(),
                new InProcessRouterState(3, 60));

        int aFirst = 0;
        int samples = 10_000;
        for (int i = 0; i < samples; i++) {
            if (router.candidates("g").get(0).name().equals("a")) {
                aFirst++;
            }
        }
        double ratio = aFirst / (double) samples;
        assertTrue(ratio > 0.60 && ratio < 0.80, "expected ~0.70, got " + ratio);
    }

    @Test
    void chainAppendsFallbackGroupsInOrder() {
        RouterService router = new RouterService(
                List.of(dep("broken", "bad", 10), dep("good", "ok", 10)),
                Map.of("broken", List.of("good")),
                new InProcessRouterState(3, 60));

        List<Deployment> chain = router.chain("broken");
        assertEquals(2, chain.size());
        assertEquals("broken", chain.get(0).group());
        assertEquals("good", chain.get(1).group());
    }

    @Test
    void consecutiveFailuresOpenTheCircuitAndRemoveItFromRotation() {
        InProcessRouterState state = new InProcessRouterState(3, 60);
        Deployment bad = dep("g", "bad", 10);
        RouterService router = router(state, bad, dep("g", "ok", 10));

        for (int i = 0; i < 3; i++) {
            router.recordFailure(bad);
        }
        assertEquals(CircuitState.OPEN, state.stateOf("bad"));
        List<Deployment> candidates = router.candidates("g");
        assertEquals(1, candidates.size());
        assertEquals("ok", candidates.get(0).name());
    }

    @Test
    void cooldownExpiryOffersASingleHalfOpenProbe() {
        MutableClock clock = new MutableClock();
        InProcessRouterState state = new InProcessRouterState(3, 60, 20, 0.5, 10, clock);
        Deployment bad = dep("g", "bad", 10);
        RouterService router = router(state, bad);

        for (int i = 0; i < 3; i++) {
            router.recordFailure(bad);
        }
        assertTrue(router.candidates("g").isEmpty());

        clock.advance(61_000);
        assertEquals(1, router.candidates("g").size(), "expired breaker becomes HALF_OPEN");
        assertEquals(CircuitState.HALF_OPEN, state.stateOf("bad"));
        assertTrue(router.candidates("g").isEmpty(), "only one probe is granted at a time");

        router.recordSuccess(bad);
        assertEquals(CircuitState.CLOSED, state.stateOf("bad"));
        assertEquals(1, router.candidates("g").size());
    }

    @Test
    void failedProbeReopensWithBackoff() {
        MutableClock clock = new MutableClock();
        InProcessRouterState state = new InProcessRouterState(3, 60, 20, 0.5, 10, clock);
        Deployment bad = dep("g", "bad", 10);
        RouterService router = router(state, bad);

        for (int i = 0; i < 3; i++) {
            router.recordFailure(bad);
        }
        clock.advance(61_000);
        router.candidates("g");                       // consumes the probe permit
        router.recordFailure(bad);                    // probe failed

        assertEquals(CircuitState.OPEN, state.stateOf("bad"));
        clock.advance(61_000);
        assertTrue(router.candidates("g").isEmpty(), "second trip waits 2x cooldown");
        clock.advance(61_000);
        assertEquals(1, router.candidates("g").size(), "half-open again after 2x cooldown");
    }

    @Test
    void failureRateTripsEvenWithoutConsecutiveFailures() {
        MutableClock clock = new MutableClock();
        // allowedFails is high on purpose: only the rate signal can trip this breaker
        InProcessRouterState state = new InProcessRouterState(5, 60, 10, 0.5, 6, clock);
        Deployment flaky = dep("g", "flaky", 10);
        RouterService router = router(state, flaky);

        for (int i = 0; i < 3; i++) {
            router.recordFailure(flaky);   // never two in a row
            router.recordSuccess(flaky);
        }
        // 6 calls, exactly 50% failures — the window is full but the last call was healthy,
        // and the breaker only re-evaluates on failure
        assertEquals(CircuitState.CLOSED, state.stateOf("flaky"));

        router.recordFailure(flaky);
        assertEquals(CircuitState.OPEN, state.stateOf("flaky"),
                "4 failures out of 7 calls = 57% >= 50% trips the breaker without any "
                        + "consecutive pair");
    }

    @Test
    void successClearsFailureHistory() {
        InProcessRouterState state = new InProcessRouterState(3, 60);
        Deployment d = dep("g", "d", 10);
        RouterService router = router(state, d);

        router.recordFailure(d);
        router.recordFailure(d);
        router.recordSuccess(d);
        router.recordFailure(d);
        router.recordFailure(d);
        assertEquals(CircuitState.CLOSED, state.stateOf("d"));

        router.recordFailure(d);
        assertEquals(CircuitState.OPEN, state.stateOf("d"));
        assertEquals(1, state.openCount("d"));
    }

    @Test
    void deploymentsAreAddressableByName() {
        RouterService router = new RouterService(
                List.of(dep("g", "alpha", 10)), Map.of(), new InProcessRouterState(3, 60));
        assertNotNull(router.deploymentByName("alpha"));
        assertEquals("g", router.deploymentByName("alpha").group());
        assertEquals(1, router.deployments().size());
    }

    @Test
    void unknownGroupYieldsEmptyChain() {
        RouterService router = router(new InProcessRouterState(3, 60), dep("g", "a", 10));
        assertTrue(router.candidates("nope").isEmpty());
        assertTrue(router.chain("nope").isEmpty());
        assertFalse(router.hasGroup("nope"));
    }
}
