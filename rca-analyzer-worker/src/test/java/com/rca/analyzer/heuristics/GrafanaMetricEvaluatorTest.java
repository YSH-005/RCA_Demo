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
    void evaluatesMongoCpuAgainstBaseline() {
        Map<String, List<Double>> incident = Map.of(
                "mongo_cpu_total", List.of(0.92, 0.95, 0.98));
        Map<String, List<Double>> baseline = Map.of(
                "mongo_cpu_total", List.of(0.40, 0.42, 0.45));

        var comparisons = evaluator.evaluate(incident, baseline, "qa6-mongo-core-40", "mongo_");
        var cpu = comparisons.stream()
                .filter(c -> "mongo_cpu_total".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(cpu.isTriggered());
        assertEquals("C_DATABASE", evaluator.categoryFor("mongo_cpu_total").name());
    }

    @Test
    void evaluatesMongoDownWhenHostDropsFromUp() {
        Map<String, List<Double>> incident = Map.of("mongo_down", List.of(0.0, 1.0));
        Map<String, List<Double>> baseline = Map.of("mongo_down", List.of(1.0, 1.0));

        var comparisons = evaluator.evaluate(incident, baseline, "qa6-mongo-core-40-b", "mongo_");
        var down = comparisons.stream()
                .filter(c -> "mongo_down".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(down.isTriggered());
        assertEquals("BELOW_IF_BASELINE_ABOVE", down.getFormula());
    }

    @Test
    void mongoDownValueOneMeansHealthy() {
        Map<String, List<Double>> incident = Map.of("mongo_down", List.of(1.0, 1.0));
        Map<String, List<Double>> baseline = Map.of("mongo_down", List.of(1.0, 1.0));

        var comparisons = evaluator.evaluate(incident, baseline, "qa6-mongo-core-40-b", "mongo_");
        var down = comparisons.stream()
                .filter(c -> "mongo_down".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertEquals(false, down.isTriggered());
    }

    @Test
    void evaluatesCpuThrottleWhenBaselineNearZero() {
        Map<String, List<Double>> incident = Map.of(
                "container_cpu_throttle_pct", List.of(18.0, 22.0, 25.0));
        Map<String, List<Double>> baseline = Map.of(
                "container_cpu_throttle_pct", List.of(0.0, 1.0, 0.5));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-pod");
        var throttle = comparisons.stream()
                .filter(c -> "container_cpu_throttle_pct".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(throttle.isTriggered());
        assertEquals("B_BACKEND_CPU", evaluator.categoryFor("container_cpu_throttle_pct").name());
    }

    @Test
    void evaluatesPodRestartsWhenBaselineWasZero() {
        Map<String, List<Double>> incident = Map.of("pod_restarts_total", List.of(1.0));
        Map<String, List<Double>> baseline = Map.of("pod_restarts_total", List.of(0.0));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-pod");
        var restarts = comparisons.stream()
                .filter(c -> "pod_restarts_total".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(restarts.isTriggered());
        assertEquals("D_EXCEPTION", evaluator.categoryFor("pod_restarts_total").name());
    }

    @Test
    void evaluatesRecentPodRestartByAge() {
        Map<String, List<Double>> incident = Map.of("pod_age_seconds", List.of(300.0, 420.0));
        Map<String, List<Double>> baseline = Map.of("pod_age_seconds", List.of(86400.0, 90000.0));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-pod");
        var age = comparisons.stream()
                .filter(c -> "pod_age_seconds".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(age.isTriggered());
        assertTrue(age.getInterpretation().contains("recently restarted"));
    }

    @Test
    void evaluatesIngress5xxWhenBaselineWasZero() {
        Map<String, List<Double>> incident = Map.of("ingress_5xx_per_min", List.of(3.0, 5.0));
        Map<String, List<Double>> baseline = Map.of("ingress_5xx_per_min", List.of(0.0));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-app-ingress-edge-ui");
        var errors = comparisons.stream()
                .filter(c -> "ingress_5xx_per_min".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(errors.isTriggered());
        assertEquals("A_NETWORK", evaluator.categoryFor("ingress_5xx_per_min").name());
    }

    @Test
    void evaluatesIngressSuccessRateDrop() {
        Map<String, List<Double>> incident = Map.of("ingress_success_rate", List.of(0.88, 0.90));
        Map<String, List<Double>> baseline = Map.of("ingress_success_rate", List.of(0.99, 0.98));

        var comparisons = evaluator.evaluate(incident, baseline, "webui-app-ingress-edge-ui");
        var success = comparisons.stream()
                .filter(c -> "ingress_success_rate".equals(c.getMetric()))
                .findFirst()
                .orElseThrow();

        assertTrue(success.isTriggered());
    }

    @Test
    void timeSeriesStats_computesP95() {
        MetricStatSummary summary = TimeSeriesStats.summarize(List.of(10.0, 20.0, 30.0, 40.0, 100.0));
        assertEquals(100.0, summary.getMax());
        assertTrue(summary.getP95() >= 40.0);
    }
}
