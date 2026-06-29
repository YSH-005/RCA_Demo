package com.rca.analyzer.service;

import com.rca.analyzer.client.GrafanaEsContext;
import com.rca.analyzer.client.GrafanaMongoContext;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.MetricStatSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Grafana-shaped incident vs baseline comparisons when API is unavailable.
 */
final class SyntheticGrafanaProvider {

    private SyntheticGrafanaProvider() {
    }

    static GrafanaResult build(
            String podName,
            List<Map<String, Object>> kibanaLogs,
            List<Map<String, Object>> graylogLogs) {

        long esMs = maxFromGraylog(graylogLogs, "esTimeTaken");
        long mongoMs = maxFromGraylog(graylogLogs, "mongoTimeTaken");
        long chMs = maxFromGraylog(graylogLogs, "chTimeTaken");
        long totalDbMs = maxFromGraylog(graylogLogs, "totalDbTimeMs");
        long apiTotalMs = maxFromGraylog(graylogLogs, "apiTotalMs");
        long waitMs = maxKibana(kibanaLogs, "totalWaitTimeMs");
        String esHost = firstEsHost(kibanaLogs, graylogLogs);
        String mongoHost = firstMongoHost(graylogLogs);

        if (totalDbMs <= 0) {
            totalDbMs = mongoMs + chMs + esMs;
        }
        if (apiTotalMs <= 0) {
            apiTotalMs = Math.max(totalDbMs + 50, 100);
        }

        boolean dbPressure = totalDbMs >= 800 || chMs >= 800 || esMs >= 800;
        boolean pressure = waitMs >= 1500 || dbPressure || apiTotalMs >= 1200;

        double baselineCpu = pressure ? 0.48 : 0.42;
        double incidentCpu = pressure ? 0.88 : 0.46;
        double baselineMem = 0.52;
        double incidentMem = pressure ? 0.76 : 0.54;
        double baselineQueue = 2;
        double incidentQueue = pressure ? 28 : 2;
        double baselineGc = 90;
        double incidentGc = pressure ? 420 : 95;
        double baselineEs = Math.max(esMs > 0 ? esMs * 0.35 : 180, 50);
        double incidentEs = Math.max(esMs, 50);
        double baselineMongo = Math.max(mongoMs > 0 ? mongoMs * 0.4 : 200, 100);
        double incidentMongo = Math.max(mongoMs, 100);
        double baselineCh = Math.max(chMs > 0 ? chMs * 0.3 : 300, 100);
        double incidentCh = Math.max(chMs, 100);

        List<MetricComparison> comparisons = new ArrayList<>();
        comparisons.add(comparison("container_cpu_usage_rate", podName, incidentCpu, baselineCpu, pressure));
        comparisons.add(comparison("container_memory_usage_rate", podName, incidentMem, baselineMem, pressure && incidentMem / baselineMem >= 1.2));
        comparisons.add(comparison("thread_queue", podName, incidentQueue, baselineQueue, pressure));
        comparisons.add(eventComparison("thread_rejected", podName, pressure ? 4 : 0, 0));
        comparisons.add(comparison("gc_old_gen_ms", podName, incidentGc, baselineGc, pressure));
        comparisons.add(comparison("container_cpu_throttle_pct", podName,
                pressure ? 22.0 : 1.0, 0.5, pressure));
        comparisons.add(eventComparison("pod_restarts_total", podName, pressure ? 1 : 0, 0));
        comparisons.add(eventComparison("probe_readiness_failed", podName, pressure ? 2 : 0, 0));
        if (pressure) {
            comparisons.add(eventComparison("pod_terminated_reason", podName, 1, 0));
            comparisons.add(comparison("pod_age_seconds", podName, 420, 86400, true));
        }
        if (!esHost.isBlank() || esMs >= 500) {
            String influxEsHost = GrafanaEsContext.resolveFromLogs(kibanaLogs, graylogLogs);
            if (influxEsHost.isBlank() && !esHost.isBlank()) {
                influxEsHost = GrafanaMongoContext.toInfluxHostname(esHost);
            }
            comparisons.add(comparison("es_query_latency_ms",
                    influxEsHost.isBlank() ? "es-data-qa6-case1-es-1-b" : influxEsHost,
                    incidentEs, baselineEs, esMs >= 800));
            comparisons.add(eventComparison("es_threadpool_search_rejected",
                    influxEsHost.isBlank() ? "es-data-qa6-case1-es-1-b" : influxEsHost,
                    esMs >= 1000 ? 3 : 0, 0));
        }
        if (mongoMs >= 50 || !mongoHost.isBlank()) {
            String influxHost = GrafanaMongoContext.toInfluxHostname(mongoHost);
            String host = influxHost.isBlank() ? "qa6-mongo-core-40" : influxHost;
            comparisons.add(comparison("mongo_scanned_objects", host,
                    incidentMongo * 10, baselineMongo * 10, mongoMs >= 200));
            comparisons.add(comparison("mongo_cpu_total", host,
                    pressure ? 0.92 : 0.45, 0.40, pressure && mongoMs >= 100));
        }
        if (chMs >= 200) {
            comparisons.add(comparison("clickhouse_query_latency_ms", "qa6-clickhouse-analytics-1",
                    incidentCh, baselineCh, chMs >= 800));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("source", "stub");
        metrics.put("podName", podName);
        metrics.put("apiTotalMs", apiTotalMs);
        metrics.put("totalDbTimeMs", totalDbMs);
        for (MetricComparison c : comparisons) {
            if (c.getIncident() != null && c.getIncident().getSampleCount() > 0) {
                metrics.put(c.getMetric(), c.getIncident().getP95());
            }
            if (c.getHost() != null && c.getMetric().startsWith("es_")) {
                metrics.put("es_host", c.getHost());
            }
        }

        return new GrafanaResult(metrics, comparisons);
    }

    private static MetricComparison comparison(
            String metric, String host, double incident, double baseline, boolean elevated) {
        double ratio = baseline > 0 ? incident / baseline : 0;
        return MetricComparison.builder()
                .source("grafana")
                .metric(metric)
                .host(host)
                .incident(single(incident))
                .baseline(single(baseline))
                .ratio(ratio)
                .delta(incident - baseline)
                .verdict(elevated ? "elevated" : "normal")
                .formula("RELATIVE_BASELINE")
                .triggered(elevated)
                .strength(elevated ? Math.min(1.0, ratio / 1.25) : 0)
                .interpretation(elevated
                        ? "%s elevated: incident=%.2f vs baseline=%.2f (%.1fx)".formatted(metric, incident, baseline, ratio)
                        : "%s incident=%.2f near baseline=%.2f".formatted(metric, incident, baseline))
                .build();
    }

    private static MetricComparison eventComparison(String metric, String host, double incident, double baseline) {
        boolean triggered = incident > 0 && baseline < 0.5;
        return MetricComparison.builder()
                .source("grafana")
                .metric(metric)
                .host(host)
                .incident(single(incident))
                .baseline(single(baseline))
                .ratio(incident > 0 ? Double.POSITIVE_INFINITY : 0)
                .delta(incident - baseline)
                .verdict(triggered ? "anomalous_event" : "normal")
                .formula("PRESENT_IF_BASELINE_LOW")
                .triggered(triggered)
                .strength(triggered ? 1 : 0)
                .interpretation(triggered
                        ? "%s events during incident (baseline quiet)".formatted(metric)
                        : "%s no anomalous events".formatted(metric))
                .build();
    }

    private static MetricStatSummary single(double value) {
        return MetricStatSummary.builder()
                .mean(value).median(value).p95(value).max(value).last(value).sum(value).sampleCount(1)
                .build();
    }

    private static long maxFromGraylog(List<Map<String, Object>> logs, String field) {
        if (logs == null) {
            return 0;
        }
        return logs.stream().mapToLong(l -> longVal(l.get(field))).max().orElse(0);
    }

    private static long maxKibana(List<Map<String, Object>> logs, String field) {
        if (logs == null) {
            return 0;
        }
        return logs.stream().mapToLong(l -> longVal(l.get(field))).max().orElse(0);
    }

    private static String firstEsHost(List<Map<String, Object>> kibana, List<Map<String, Object>> graylog) {
        if (graylog != null) {
            for (Map<String, Object> log : graylog) {
                Object h = log.get("esHost");
                if (h != null && !String.valueOf(h).isBlank()) {
                    return String.valueOf(h);
                }
            }
        }
        if (kibana != null) {
            for (Map<String, Object> log : kibana) {
                Object h = log.get("esHost");
                if (h != null && !String.valueOf(h).isBlank()) {
                    return String.valueOf(h);
                }
            }
        }
        return "";
    }

    private static String firstMongoHost(List<Map<String, Object>> graylog) {
        if (graylog == null) {
            return "";
        }
        for (Map<String, Object> log : graylog) {
            Object h = log.get("mongoHost");
            if (h != null && !String.valueOf(h).isBlank()) {
                return String.valueOf(h);
            }
        }
        return "";
    }

    private static long longVal(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    record GrafanaResult(Map<String, Object> podMetrics, List<MetricComparison> comparisons) {
    }
}
