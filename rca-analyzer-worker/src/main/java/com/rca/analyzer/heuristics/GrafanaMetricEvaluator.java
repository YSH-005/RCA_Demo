package com.rca.analyzer.heuristics;

import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.MetricStatSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GrafanaMetricEvaluator {

    private final HeuristicsProperties properties;

    public GrafanaMetricEvaluator(HeuristicsProperties properties) {
        this.properties = properties;
    }

    public List<MetricComparison> evaluate(
            Map<String, List<Double>> incidentSeries,
            Map<String, List<Double>> baselineSeries,
            String host) {

        List<MetricComparison> comparisons = new ArrayList<>();
        for (Map.Entry<String, HeuristicsProperties.GrafanaMetricRule> entry : metricRules().entrySet()) {
            String metric = entry.getKey();
            HeuristicsProperties.GrafanaMetricRule rule = entry.getValue();

            MetricStatSummary incident = TimeSeriesStats.summarize(incidentSeries.get(metric));
            MetricStatSummary baseline = TimeSeriesStats.summarize(baselineSeries.get(metric));
            if (incident.getSampleCount() == 0 && baseline.getSampleCount() == 0) {
                continue;
            }

            double incidentValue = TimeSeriesStats.pick(incident, rule.getIncidentAggregation());
            double baselineValue = TimeSeriesStats.pick(baseline, rule.getBaselineAggregation());
            StatFormula formula = StatFormula.valueOf(rule.getFormula());

            boolean triggered = evaluateTriggered(formula, incidentValue, baselineValue, rule, metric);
            double ratio = baselineValue > 0 ? incidentValue / baselineValue : (incidentValue > 0 ? Double.POSITIVE_INFINITY : 0);
            double delta = incidentValue - baselineValue;
            double strength = triggered
                    ? StatEvaluator.scoreContribution(formula, incidentValue, buildContext(formula, baselineValue, rule, metric), 1.0)
                    : 0;

            comparisons.add(MetricComparison.builder()
                    .source("grafana")
                    .metric(metric)
                    .host(host)
                    .incident(incident)
                    .baseline(baseline)
                    .ratio(Double.isFinite(ratio) ? ratio : 0)
                    .delta(delta)
                    .verdict(verdict(formula, triggered, incident.getSampleCount(), baseline.getSampleCount()))
                    .formula(formula.name())
                    .triggered(triggered)
                    .strength(strength)
                    .interpretation(interpretation(metric, incidentValue, baselineValue, ratio, triggered))
                    .build());
        }
        return comparisons;
    }

    public Map<String, Object> flattenForLegacyMetrics(List<MetricComparison> comparisons) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (MetricComparison c : comparisons) {
            if (c.getIncident() != null && c.getIncident().getSampleCount() > 0) {
                HeuristicsProperties.GrafanaMetricRule rule = metricRules().get(c.getMetric());
                String agg = rule != null ? rule.getIncidentAggregation() : "last";
                flat.put(c.getMetric(), TimeSeriesStats.pick(c.getIncident(), agg));
            }
        }
        return flat;
    }

    private Map<String, HeuristicsProperties.GrafanaMetricRule> metricRules() {
        Map<String, HeuristicsProperties.GrafanaMetricRule> rules = properties.getGrafanaMetrics();
        return rules == null || rules.isEmpty() ? defaultRules() : rules;
    }

    private boolean evaluateTriggered(
            StatFormula formula, double incident, double baseline,
            HeuristicsProperties.GrafanaMetricRule rule, String metric) {
        return StatEvaluator.triggered(formula, incident, buildContext(formula, baseline, rule, metric));
    }

    private StatEvaluator.EvalContext buildContext(
            StatFormula formula, double baseline, HeuristicsProperties.GrafanaMetricRule rule, String metric) {
        return switch (formula) {
            case RELATIVE_BASELINE -> StatEvaluator.EvalContext.relativeBaseline(
                    baseline, rule.getMultiplier(), rule.getMinDelta());
            case BASELINE_MULTIPLIER -> StatEvaluator.EvalContext.baselineMultiplier(
                    baseline > 0 ? baseline : fallbackBaseline(metric),
                    rule.getMultiplier());
            case PRESENT_IF_BASELINE_LOW -> StatEvaluator.EvalContext.presentIfBaselineLow(
                    baseline, rule.getBaselineCeiling());
            case THRESHOLD -> StatEvaluator.EvalContext.threshold(rule.getFloor());
            default -> StatEvaluator.EvalContext.baselineMultiplier(baseline, rule.getMultiplier());
        };
    }

    private double fallbackBaseline(String metric) {
        return switch (metric) {
            case "container_cpu_usage_rate" -> properties.getBaselines().getContainerCpuUsageRate();
            case "container_memory_usage_rate" -> properties.getBaselines().getContainerMemoryUsageRate();
            case "thread_queue" -> properties.getBaselines().getThreadQueue();
            case "gc_old_gen_ms" -> properties.getBaselines().getGcOldGenMs();
            case "es_query_latency_ms" -> properties.getThresholds().getKibanaEsTimeMs();
            default -> 1;
        };
    }

    private String verdict(StatFormula formula, boolean triggered, int incidentN, int baselineN) {
        if (incidentN == 0) {
            return "insufficient_data";
        }
        if (baselineN == 0 && formula != StatFormula.PRESENT_IF_BASELINE_LOW) {
            return triggered ? "elevated" : "normal";
        }
        if (formula == StatFormula.PRESENT_IF_BASELINE_LOW && triggered) {
            return "anomalous_event";
        }
        return triggered ? "elevated" : "normal";
    }

    private String interpretation(String metric, double incident, double baseline, double ratio, boolean triggered) {
        if (!triggered) {
            if (baseline > 0 && ratio < 1.15) {
                return "%s incident=%.2f near baseline=%.2f (chronically high but not anomalous)".formatted(
                        metric, incident, baseline);
            }
            return "%s incident=%.2f baseline=%.2f within normal range".formatted(metric, incident, baseline);
        }
        return "%s elevated: incident=%.2f vs baseline=%.2f (%.1fx)".formatted(
                metric, incident, baseline, ratio);
    }

    public BottleneckCategory categoryFor(String metric) {
        HeuristicsProperties.GrafanaMetricRule rule = metricRules().get(metric);
        if (rule == null || rule.getCategory() == null || rule.getCategory().isBlank()) {
            return metric.startsWith("es_") || metric.startsWith("mongo_")
                    ? BottleneckCategory.C_DATABASE
                    : BottleneckCategory.B_BACKEND_CPU;
        }
        return BottleneckCategory.valueOf(rule.getCategory());
    }

    public double weightFor(String metric) {
        HeuristicsProperties.GrafanaMetricRule rule = metricRules().get(metric);
        return rule != null ? rule.getWeight() : 0.8;
    }

    private static Map<String, HeuristicsProperties.GrafanaMetricRule> defaultRules() {
        Map<String, HeuristicsProperties.GrafanaMetricRule> rules = new LinkedHashMap<>();
        rules.put("container_cpu_usage_rate", rule("p95", "median", "RELATIVE_BASELINE", 1.25, 0.10,
                "B_BACKEND_CPU", 1.0, 0, 0));
        rules.put("container_memory_usage_rate", rule("p95", "median", "RELATIVE_BASELINE", 1.20, 0.08,
                "B_BACKEND_CPU", 0.8, 0, 0));
        rules.put("thread_queue", rule("p95", "p95", "RELATIVE_BASELINE", 2.0, 3,
                "B_BACKEND_CPU", 0.7, 0, 0));
        rules.put("thread_rejected", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "B_BACKEND_CPU", 1.2, 0, 0.5));
        rules.put("gc_old_gen_ms", rule("max", "p95", "RELATIVE_BASELINE", 2.0, 50,
                "B_BACKEND_CPU", 0.9, 0, 0));
        rules.put("es_query_latency_ms", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 200,
                "C_DATABASE", 0.9, 500, 0));
        rules.put("es_threadpool_search_rejected", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 1.0, 0, 0.5));
        rules.put("mongo_scanned_objects", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 500,
                "C_DATABASE", 0.8, 0, 0));
        return rules;
    }

    private static HeuristicsProperties.GrafanaMetricRule rule(
            String incidentAgg, String baselineAgg, String formula,
            double multiplier, double minDelta, String category, double weight,
            double floor, double baselineCeiling) {
        HeuristicsProperties.GrafanaMetricRule r = new HeuristicsProperties.GrafanaMetricRule();
        r.setIncidentAggregation(incidentAgg);
        r.setBaselineAggregation(baselineAgg);
        r.setFormula(formula);
        r.setMultiplier(multiplier);
        r.setMinDelta(minDelta);
        r.setCategory(category);
        r.setWeight(weight);
        r.setFloor(floor);
        r.setBaselineCeiling(baselineCeiling);
        return r;
    }
}
