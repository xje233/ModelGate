package io.modelgate.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.modelgate.core.Deployment;

class RouterServiceTest {

    private static Deployment dep(String group, String name, int weight) {
        return new Deployment(name, group, "mock", name,
                "http://localhost:9" + name.hashCode() % 10, null, weight, null);
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
        assertTrue(ratio > 0.60 && ratio < 0.80,
                "expected ~0.70, got " + ratio);
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
    void consecutiveFailuresPutDeploymentIntoCooldown() {
        AtomicLong now = new AtomicLong(1_000_000);
        InProcessRouterState state = new InProcessRouterState(3, 60, now::get);
        Deployment bad = dep("g", "bad", 10);
        RouterService router = new RouterService(
                List.of(bad, dep("g", "ok", 10)), Map.of(), state);

        for (int i = 0; i < 3; i++) {
            router.recordFailure(bad);
        }
        List<Deployment> candidates = router.candidates("g");
        assertEquals(1, candidates.size());
        assertEquals("ok", candidates.get(0).name());

        // cooldown expiry (HALF-OPEN) lets it back in
        now.addAndGet(61_000);
        assertEquals(2, router.candidates("g").size());
    }

    @Test
    void successResetsFailureCounter() {
        AtomicLong now = new AtomicLong(1_000_000);
        InProcessRouterState state = new InProcessRouterState(3, 60, now::get);
        Deployment d = dep("g", "d", 10);
        RouterService router = new RouterService(List.of(d), Map.of(), state);

        router.recordFailure(d);
        router.recordFailure(d);
        router.recordSuccess(d);
        router.recordFailure(d);
        router.recordFailure(d);
        assertFalse(state.isCoolingDown("d"));

        router.recordFailure(d);
        assertTrue(state.isCoolingDown("d"));
    }

    @Test
    void unknownGroupYieldsEmptyChain() {
        RouterService router = new RouterService(List.of(dep("g", "a", 10)), Map.of(),
                new InProcessRouterState(3, 60));
        assertTrue(router.candidates("nope").isEmpty());
        assertTrue(router.chain("nope").isEmpty());
        assertFalse(router.hasGroup("nope"));
    }
}
