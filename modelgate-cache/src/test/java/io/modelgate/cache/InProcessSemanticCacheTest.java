package io.modelgate.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InProcessSemanticCacheTest {

    private static double[] vector(double... values) {
        return values;
    }

    @Test
    void identicalPromptHitsAndReturnsTheStoredPayload() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("sc:key-a:fast-medium:abc", "e1", vector(0.1, 0.2, 0.3), "{\"answer\":42}").block();

        CacheLookup lookup = cache.find("sc:key-a:fast-medium:abc", vector(0.1, 0.2, 0.3), 0.95).block();
        assertTrue(lookup.hit());
        assertEquals(1.0, lookup.similarity(), 1e-9);
        assertEquals("{\"answer\":42}", lookup.payload());
    }

    @Test
    void unrelatedPromptMisses() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("ns", "e1", vector(1, 0, 0), "payload").block();

        CacheLookup lookup = cache.find("ns", vector(0, 0, 1), 0.95).block();
        assertFalse(lookup.hit());
    }

    @Test
    void thresholdIsHonoured() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("ns", "e1", vector(1, 1, 0), "payload").block();

        double[] probe = vector(1, 0.2, 0);
        double similarity = CosineSimilarity.of(vector(1, 1, 0), probe);
        assertTrue(similarity > 0.8 && similarity < 0.95, "test vector must sit between thresholds");

        assertFalse(cache.find("ns", probe, 0.95).block().hit());
        assertTrue(cache.find("ns", probe, 0.80).block().hit());
    }

    /** The isolation property that stops one caller's answer leaking to another. */
    @Test
    void namespacesAreIsolated() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("sc:key-a:fast-medium:x", "e1", vector(1, 1, 1), "A's answer").block();

        CacheLookup other = cache.find("sc:key-b:fast-medium:x", vector(1, 1, 1), 0.95).block();
        assertFalse(other.hit(), "same prompt from another key must not hit");
    }

    @Test
    void modelDimensionIsPartOfTheIsolationBoundary() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("sc:key-a:fast-medium:x", "e1", vector(1, 1, 1), "cheap model answer").block();

        assertFalse(cache.find("sc:key-a:smart:x", vector(1, 1, 1), 0.95).block().hit());
    }

    @Test
    void entryCountIsBoundedPerNamespace() {
        InProcessSemanticCache cache = new InProcessSemanticCache(2, 60);
        cache.put("ns", "e1", vector(1, 0, 0), "one").block();
        cache.put("ns", "e2", vector(0, 1, 0), "two").block();
        cache.put("ns", "e3", vector(0, 0, 1), "three").block();

        assertEquals(2L, cache.size("ns").block());
        assertFalse(cache.find("ns", vector(1, 0, 0), 0.95).block().hit(), "oldest entry evicted");
        assertTrue(cache.find("ns", vector(0, 0, 1), 0.95).block().hit());
    }

    @Test
    void clearEmptiesEveryNamespace() {
        InProcessSemanticCache cache = new InProcessSemanticCache(10, 60);
        cache.put("ns", "e1", vector(1, 0), "payload").block();
        cache.clear().block();
        assertEquals(0L, cache.size("ns").block());
    }
}
