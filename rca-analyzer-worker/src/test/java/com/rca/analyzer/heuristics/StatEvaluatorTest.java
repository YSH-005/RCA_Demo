package com.rca.analyzer.heuristics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatEvaluatorTest {

    @Test
    void shareOfTotal_triggersWhenAboveThreshold() {
        var ctx = StatEvaluator.EvalContext.share(1000, 0.70);
        assertTrue(StatEvaluator.triggered(StatFormula.SHARE_OF_TOTAL, 750, ctx));
        assertFalse(StatEvaluator.triggered(StatFormula.SHARE_OF_TOTAL, 600, ctx));
    }

    @Test
    void baselineMultiplier_triggersWhenExceeded() {
        var ctx = StatEvaluator.EvalContext.baselineMultiplier(0.55, 1.25);
        assertTrue(StatEvaluator.triggered(StatFormula.BASELINE_MULTIPLIER, 0.70, ctx));
        assertFalse(StatEvaluator.triggered(StatFormula.BASELINE_MULTIPLIER, 0.60, ctx));
    }

    @Test
    void medianDeviation_requiresMultipleSamples() {
        var ctx = StatEvaluator.EvalContext.medianDeviation(List.of(100.0, 110.0, 105.0), 0.50);
        assertFalse(StatEvaluator.triggered(StatFormula.MEDIAN_DEVIATION, 108, ctx));
        assertTrue(StatEvaluator.triggered(StatFormula.MEDIAN_DEVIATION, 200, ctx));
    }

    @Test
    void relativeBaseline_requiresRatioAndDelta() {
        var ctx = StatEvaluator.EvalContext.relativeBaseline(0.48, 1.25, 0.10);
        assertFalse(StatEvaluator.triggered(StatFormula.RELATIVE_BASELINE, 0.50, ctx));
        assertTrue(StatEvaluator.triggered(StatFormula.RELATIVE_BASELINE, 0.88, ctx));
    }

    @Test
    void relativeBaseline_ignoresChronicallyHighPod() {
        var ctx = StatEvaluator.EvalContext.relativeBaseline(0.82, 1.25, 0.10);
        assertFalse(StatEvaluator.triggered(StatFormula.RELATIVE_BASELINE, 0.85, ctx));
    }

    @Test
    void presentIfBaselineLow_triggersOnNewEvents() {
        var ctx = StatEvaluator.EvalContext.presentIfBaselineLow(0, 0.5);
        assertTrue(StatEvaluator.triggered(StatFormula.PRESENT_IF_BASELINE_LOW, 4, ctx));
        assertFalse(StatEvaluator.triggered(StatFormula.PRESENT_IF_BASELINE_LOW, 0, ctx));
    }
}
