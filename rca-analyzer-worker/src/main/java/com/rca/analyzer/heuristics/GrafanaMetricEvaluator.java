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
        return evaluate(incidentSeries, baselineSeries, host, null);
    }

    public List<MetricComparison> evaluate(
            Map<String, List<Double>> incidentSeries,
            Map<String, List<Double>> baselineSeries,
            String host,
            String metricPrefix) {

        List<MetricComparison> comparisons = new ArrayList<>();
        for (Map.Entry<String, HeuristicsProperties.GrafanaMetricRule> entry : metricRules().entrySet()) {
            String metric = entry.getKey();
            if (metricPrefix != null && !metric.startsWith(metricPrefix)) {
                continue;
            }
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
                double value = TimeSeriesStats.pick(c.getIncident(), agg);
                flat.merge(c.getMetric(), value, (a, b) -> Math.max(((Number) a).doubleValue(), ((Number) b).doubleValue()));
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
            case BELOW_THRESHOLD -> StatEvaluator.EvalContext.belowThreshold(rule.getFloor());
            case BELOW_IF_BASELINE_ABOVE -> StatEvaluator.EvalContext.belowIfBaselineAbove(
                    rule.getFloor(), baseline, rule.getBaselineCeiling());
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
            case "mongo_cpu_total" -> properties.getBaselines().getMongoCpuTotal();
            case "mongo_cpu_iowait" -> properties.getBaselines().getMongoCpuIowaitPct();
            case "mongo_load_avg" -> properties.getBaselines().getMongoLoadAvg();
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
        if ((formula == StatFormula.PRESENT_IF_BASELINE_LOW || formula == StatFormula.BELOW_IF_BASELINE_ABOVE)
                && triggered) {
            return "anomalous_event";
        }
        if (formula == StatFormula.BELOW_THRESHOLD && triggered) {
            return "anomalous_event";
        }
        return triggered ? "elevated" : "normal";
    }

    private String interpretation(String metric, double incident, double baseline, double ratio, boolean triggered) {
        if ("mongo_down".equals(metric)) {
            if (!triggered) {
                return "mongo_down incident=%.0f baseline=%.0f (1=up, 0=down) — host healthy in window"
                        .formatted(incident, baseline);
            }
            return "mongo_down dropped: incident min=%.0f vs baseline=%.0f (1=up, 0=down)".formatted(incident, baseline);
        }
        if ("es_cluster_health_status".equals(metric)) {
            if (!triggered) {
                return "es_cluster_health incident=%.0f baseline=%.0f (0=green, 1=yellow, 2=red)".formatted(
                        incident, baseline);
            }
            return "es_cluster_health degraded: incident max=%.0f vs baseline=%.0f (0=green, 1=yellow, 2=red)"
                    .formatted(incident, baseline);
        }
        if ("container_cpu_throttle_pct".equals(metric)) {
            if (!triggered) {
                return "CPU throttle incident=%.1f%% baseline=%.1f%% — within normal range".formatted(incident, baseline);
            }
            return "CPU throttle elevated: incident=%.1f%% vs baseline=%.1f%% (pod hitting CPU limit)".formatted(
                    incident, baseline);
        }
        if ("pod_restarts_total".equals(metric)) {
            if (!triggered) {
                return "Pod restarts incident=%.0f baseline=%.0f in window".formatted(incident, baseline);
            }
            return "Pod restarted %.0f time(s) during incident window (baseline %.0f)".formatted(incident, baseline);
        }
        if ("probe_liveness_failed".equals(metric) || "probe_readiness_failed".equals(metric)) {
            String probe = metric.contains("liveness") ? "Liveness" : "Readiness";
            if (!triggered) {
                return "%s probe failures incident=%.0f baseline=%.0f".formatted(probe, incident, baseline);
            }
            return "%s probe failed %.0f time(s) during incident (baseline %.0f)".formatted(
                    probe, incident, baseline);
        }
        if ("pod_terminated_reason".equals(metric)) {
            if (!triggered) {
                return "No container termination reason recorded (incident=%.0f baseline=%.0f)".formatted(
                        incident, baseline);
            }
            return "Container last terminated reason present (OOMKilled/CrashLoop/etc.)";
        }
        if ("pod_age_seconds".equals(metric)) {
            if (!triggered) {
                return "Pod age incident=%.0fs baseline=%.0fs — not a recent restart".formatted(incident, baseline);
            }
            return "Pod recently restarted: age=%.0fs during incident (%.0f min)".formatted(
                    incident, incident / 60);
        }
        if (metric.startsWith("ingress_")) {
            return ingressInterpretation(metric, incident, baseline, ratio, triggered);
        }
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

    private String ingressInterpretation(String metric, double incident, double baseline, double ratio, boolean triggered) {
        return switch (metric) {
            case "ingress_5xx_per_min" -> triggered
                    ? "Ingress 5xx rate elevated: %.1f vs baseline %.1f per window".formatted(incident, baseline)
                    : "Ingress 5xx incident=%.1f baseline=%.1f".formatted(incident, baseline);
            case "ingress_4xx_per_min" -> triggered
                    ? "Ingress 4xx rate elevated: %.1f vs baseline %.1f per window".formatted(incident, baseline)
                    : "Ingress 4xx incident=%.1f baseline=%.1f".formatted(incident, baseline);
            case "ingress_p95_latency_seconds", "ingress_p99_latency_seconds" -> triggered
                    ? "Ingress %s latency elevated: %.2fs vs baseline %.2fs (%.1fx)".formatted(
                            metric.contains("p99") ? "p99" : "p95", incident, baseline, ratio)
                    : "Ingress latency %s incident=%.2fs baseline=%.2fs".formatted(
                            metric.contains("p99") ? "p99" : "p95", incident, baseline);
            case "ingress_success_rate" -> triggered
                    ? "Ingress success rate dropped to %.1f%% (baseline %.1f%%)".formatted(
                            incident * 100, baseline * 100)
                    : "Ingress success rate incident=%.1f%% baseline=%.1f%%".formatted(
                            incident * 100, baseline * 100);
            default -> triggered
                    ? "%s elevated: incident=%.2f vs baseline=%.2f".formatted(metric, incident, baseline)
                    : "%s incident=%.2f baseline=%.2f within range".formatted(metric, incident, baseline);
        };
    }

    public BottleneckCategory categoryFor(String metric) {
        HeuristicsProperties.GrafanaMetricRule rule = metricRules().get(metric);
        if (rule == null || rule.getCategory() == null || rule.getCategory().isBlank()) {
            if (metric.startsWith("ingress_")) {
                return BottleneckCategory.A_NETWORK;
            }
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
        rules.put("container_cpu_throttle_pct", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 5,
                "B_BACKEND_CPU", 1.0, 0, 0));
        rules.put("pod_restarts_total", rule("max", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "D_EXCEPTION", 1.1, 0, 0.5));
        rules.put("probe_liveness_failed", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "D_EXCEPTION", 1.0, 0, 0.5));
        rules.put("probe_readiness_failed", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "D_EXCEPTION", 0.95, 0, 0.5));
        rules.put("pod_terminated_reason", rule("max", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "D_EXCEPTION", 1.15, 0, 0.5));
        rules.put("pod_age_seconds", rule("min", "min", "BELOW_THRESHOLD", 1, 0,
                "D_EXCEPTION", 1.0, 900, 0));
        rules.put("es_query_latency_ms", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 200,
                "C_DATABASE", 0.9, 500, 0));
        rules.put("es_threadpool_search_rejected", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 1.0, 0, 0.5));
        rules.put("es_threadpool_search_queue", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 10,
                "C_DATABASE", 0.7, 0, 0));
        rules.put("es_threadpool_search_active", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 5,
                "C_DATABASE", 0.75, 0, 0));
        rules.put("es_threadpool_write_rejected", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 1.0, 0, 0.5));
        rules.put("es_breakers_request_tripped", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 1.0, 0, 0.5));
        rules.put("es_breakers_fielddata_tripped", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 0.95, 0, 0.5));
        // cluster_health_status: 0=green, 1=yellow, 2=red — fire when degraded from green baseline.
        rules.put("es_cluster_health_status", rule("max", "min", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "C_DATABASE", 1.1, 0, 0.5));
        rules.put("mongo_scanned_objects", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 500,
                "C_DATABASE", 0.8, 0, 0));
        rules.put("mongo_scanned", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 100,
                "C_DATABASE", 0.75, 0, 0));
        rules.put("mongo_connections_current", rule("p95", "median", "RELATIVE_BASELINE", 1.5, 50,
                "C_DATABASE", 0.7, 0, 0));
        // MongoDown_mongo_down: 1 = up, 0 = down — use incident min to catch any downtime in the window.
        rules.put("mongo_down", rule("min", "min", "BELOW_IF_BASELINE_ABOVE", 1, 0,
                "C_DATABASE", 1.2, 0.5, 0.5));
        rules.put("mongo_cpu_total", rule("p95", "median", "RELATIVE_BASELINE", 1.5, 0.10,
                "C_DATABASE", 0.85, 0, 0));
        rules.put("mongo_cpu_iowait", rule("p95", "median", "RELATIVE_BASELINE", 1.5, 5,
                "C_DATABASE", 0.9, 20, 0));
        rules.put("mongo_load_avg", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 1.0,
                "C_DATABASE", 0.8, 0, 0));
        rules.put("ingress_5xx_per_min", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "A_NETWORK", 1.1, 0, 0.5));
        rules.put("ingress_4xx_per_min", rule("sum", "max", "PRESENT_IF_BASELINE_LOW", 1, 0,
                "A_NETWORK", 0.85, 0, 0.5));
        rules.put("ingress_p95_latency_seconds", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 0.5,
                "A_NETWORK", 1.0, 0, 0));
        rules.put("ingress_p99_latency_seconds", rule("p95", "median", "RELATIVE_BASELINE", 2.0, 1.0,
                "A_NETWORK", 0.9, 0, 0));
        rules.put("ingress_success_rate", rule("min", "min", "BELOW_THRESHOLD", 1, 0,
                "A_NETWORK", 1.0, 0.95, 0));
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
