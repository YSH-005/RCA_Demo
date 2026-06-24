package com.rca.analyzer.heuristics;

import com.rca.common.model.MetricStatSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrafanaMetricEvaluatorTest {

    private final GrafanaMetricEvaluator evaluator = new GrafanaMetricEvaluator(new com.rca.analyzer.config.HeuristicsProperties());

    @Test
    void evaluatesElevatedCpuAgainstBaseline() {
        Map<String, List<Double>> incident = Map.of(
                "container_cpu_usage_rate", List.of(0.80, 0.85, 0.88));
        Map<String, List<Double>> baseline = Map.of(
                "container_cpu_usage_rate", List.of(0.42, 0.45, 0.48, 0.46));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-pod");
        var cpu = comparisons.stream()
                .filter(c -> "container_cpu_usage_rate".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(cpu.isTriggered());
        assertEquals("elevated", cpu.getVerdict());
        assertTrue(cpu.getRatio() > 1.25);
    }

    @Test
    void ignoresChronicallyHighCpu() {
        Map<String, List<Double>> incident = Map.of(
                "container_cpu_usage_rate", List.of(0.83, 0.85));
        Map<String, List<Double>> baseline = Map.of(
                "container_cpu_usage_rate", List.of(0.80, 0.82, 0.81));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-pod");
        var cpu = comparisons.stream()
                .filter(c -> "container_cpu_usage_rate".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertEquals("normal", cpu.getVerdict());
    }

    @Test
    void timeSeriesStats_computesP95() {
        MetricStatSummary summary = TimeSeriesStats.summarize(List.of(10.0, 20.0, 30.0, 40.0, 100.0));
        assertEquals(100.0, summary.getMax());
        assertTrue(summary.getP95() >= 40.0);
    }
}
