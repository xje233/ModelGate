package io.modelgate.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CosineSimilarityTest {

    @Test
    void identicalVectorsScoreOne() {
        double[] v = {0.3, 0.4, 0.5};
        assertEquals(1.0, CosineSimilarity.of(v, v), 1e-9);
    }

    @Test
    void scaleDoesNotMatter() {
        assertEquals(1.0, CosineSimilarity.of(new double[]{1, 0}, new double[]{7, 0}), 1e-9);
    }

    @Test
    void orthogonalVectorsScoreZero() {
        assertEquals(0.0, CosineSimilarity.of(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
    }

    @Test
    void nearDuplicatesScoreHighEnoughToHitA095Threshold() {
        double[] a = {0.5, 0.5, 0.5, 0.5};
        double[] b = {0.5, 0.5, 0.5, 0.49};
        assertTrue(CosineSimilarity.of(a, b) > 0.95);
    }

    @Test
    void unrelatedContentScoresLow() {
        double[] a = {1, 0, 0, 0};
        double[] b = {0, 0, 0, 1};
        assertTrue(CosineSimilarity.of(a, b) < 0.1);
    }

    @Test
    void degenerateInputsNeverMatch() {
        assertEquals(0.0, CosineSimilarity.of(new double[]{0, 0}, new double[]{1, 1}), 1e-9);
        assertEquals(0.0, CosineSimilarity.of(new double[]{1, 2, 3}, new double[]{1, 2}), 1e-9);
        assertEquals(0.0, CosineSimilarity.of(null, new double[]{1}), 1e-9);
        assertEquals(0.0, CosineSimilarity.of(new double[0], new double[0]), 1e-9);
    }
}
