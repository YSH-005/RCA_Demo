package com.rca.analyzer.service;

import com.rca.analyzer.client.GrafanaClient;
import com.rca.analyzer.client.GraylogClient;
import com.rca.analyzer.client.KibanaClient;
import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.analyzer.config.ObservabilityProperties;
import com.rca.analyzer.heuristics.GrafanaMetricEvaluator;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.Telemetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private final ObservabilityProperties properties;
    private final HeuristicsProperties heuristicsProperties;
    private final GraylogClient graylogClient;
    private final KibanaClient kibanaClient;
    private final GrafanaClient grafanaClient;
    private final GrafanaMetricEvaluator grafanaMetricEvaluator;

    public Telemetry collect(String requestId, Instant from, Instant to) {
        return collect(requestId, from, to, HarStitchContext.empty(), null);
    }

    public Telemetry collect(String requestId, Instant from, Instant to, HarStitchContext har) {
        return collect(requestId, from, to, har, null);
    }

    public Telemetry collect(
            String requestId, Instant from, Instant to,
            HarStitchContext har, Instant eventTime) {

        Telemetry telemetry = new Telemetry();
        telemetry.setQueryWindowStart(from.toString());
        telemetry.setQueryWindowEnd(to.toString());
        if (eventTime != null) {
            telemetry.setEventTimestamp(eventTime.toString());
        }

        List<Map<String, Object>> kibanaLogs = fetchKibanaLogs(requestId, from, to);
        telemetry.setKibanaLogs(kibanaLogs);

        List<Map<String, Object>> graylogLogs = fetchGraylogLogs(requestId, from, to, har, kibanaLogs);
        telemetry.setGraylogLogs(graylogLogs);

        String podName = resolvePodName(graylogLogs, kibanaLogs);
        telemetry.setPodName(podName);

        Instant incidentAnchor = eventTime != null ? eventTime : from;
        GrafanaBundle grafana = fetchGrafanaBundle(podName, from, to, incidentAnchor, kibanaLogs, graylogLogs);
        telemetry.setPodMetrics(grafana.podMetrics());
        telemetry.setMetricComparisons(grafana.comparisons());

        telemetry.setLogLines(flattenLogs(graylogLogs, kibanaLogs, grafana.podMetrics()));
        return telemetry;
    }

    private GrafanaBundle fetchGrafanaBundle(
            String podName, Instant incidentFrom, Instant incidentTo, Instant eventTime,
            List<Map<String, Object>> kibanaLogs, List<Map<String, Object>> graylogLogs) {

        if (properties.useGrafanaStub()) {
            log.debug("Grafana provider: synthetic for pod={}", podName);
            SyntheticGrafanaProvider.GrafanaResult result =
                    SyntheticGrafanaProvider.build(podName, kibanaLogs, graylogLogs);
            return new GrafanaBundle(result.podMetrics(), result.comparisons());
        }

        int baselineHours = heuristicsProperties.getGrafana().getBaselineHours();
        int gapMinutes = heuristicsProperties.getGrafana().getBaselineGapMinutes();
        Instant baselineTo = incidentFrom.minus(gapMinutes, ChronoUnit.MINUTES);
        Instant baselineFrom = baselineTo.minus(baselineHours, ChronoUnit.HOURS);

        Map<String, List<Double>> incidentSeries =
                grafanaClient.fetchTimeSeries(podName, incidentFrom, incidentTo);
        Map<String, List<Double>> baselineSeries =
                grafanaClient.fetchTimeSeries(podName, baselineFrom, baselineTo);

        if (incidentSeries.isEmpty()) {
            log.warn("Grafana returned no incident series for pod={} — using synthetic grafana", podName);
            SyntheticGrafanaProvider.GrafanaResult result =
                    SyntheticGrafanaProvider.build(podName, kibanaLogs, graylogLogs);
            return new GrafanaBundle(result.podMetrics(), result.comparisons());
        }

        List<MetricComparison> comparisons =
                grafanaMetricEvaluator.evaluate(incidentSeries, baselineSeries, podName);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("source", "grafana");
        metrics.put("podName", podName);
        metrics.putAll(grafanaMetricEvaluator.flattenForLegacyMetrics(comparisons));
        metrics.put("baselineWindowStart", baselineFrom.toString());
        metrics.put("baselineWindowEnd", baselineTo.toString());
        return new GrafanaBundle(metrics, comparisons);
    }

    private List<Map<String, Object>> fetchKibanaLogs(String requestId, Instant from, Instant to) {
        List<Map<String, Object>> kibanaLogs = new ArrayList<>(kibanaClient.fetchMonitoringLogs(requestId, from, to));
        if (properties.useSeparateKibanaErrorIndex()) {
            kibanaClient.fetchErrorLogs(requestId, from, to).forEach(log -> {
                Object logId = log.get("id");
                if (logId == null || kibanaLogs.stream().noneMatch(existing -> logId.equals(existing.get("id")))) {
                    kibanaLogs.add(log);
                }
            });
        }
        return kibanaLogs;
    }

    private List<Map<String, Object>> fetchGraylogLogs(
            String requestId, Instant from, Instant to,
            HarStitchContext har, List<Map<String, Object>> kibanaLogs) {

        if (properties.useGraylogStub()) {
            log.debug("Graylog provider: synthetic for requestId={}", requestId);
            return SyntheticGraylogProvider.build(requestId, har, kibanaLogs);
        }

        List<Map<String, Object>> logs = graylogClient.fetchLogs(requestId, from, to);
        if (logs.isEmpty()) {
            log.warn("Graylog returned no results for requestId={} — using synthetic graylog", requestId);
            return SyntheticGraylogProvider.build(requestId, har, kibanaLogs);
        }
        return logs;
    }

    private String resolvePodName(List<Map<String, Object>> graylogLogs, List<Map<String, Object>> kibanaLogs) {
        String fromKibana = kibanaLogs.stream()
                .map(m -> (String) m.get("podName"))
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse("");
        if (!fromKibana.isBlank()) {
            return fromKibana;
        }

        return graylogLogs.stream()
                .map(m -> firstNonBlank((String) m.get("podName"), (String) m.get("hostname")))
                .filter(p -> p != null && !p.isBlank() && !p.startsWith("nginx-ingress"))
                .findFirst()
                .orElse(graylogLogs.stream()
                        .map(m -> (String) m.get("hostname"))
                        .filter(p -> p != null && !p.isBlank())
                        .findFirst()
                        .orElse("unknown-pod"));
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null ? b : "";
    }

    private List<String> flattenLogs(List<Map<String, Object>> graylogLogs,
                                     List<Map<String, Object>> kibanaLogs,
                                     Map<String, Object> podMetrics) {
        List<String> lines = new ArrayList<>();
        kibanaLogs.forEach(m -> lines.add("[kibana] " + m));
        graylogLogs.forEach(m -> lines.add("[graylog] " + m));
        if (!podMetrics.isEmpty()) {
            lines.add("[grafana] " + podMetrics);
        }
        return lines;
    }

    private record GrafanaBundle(Map<String, Object> podMetrics, List<MetricComparison> comparisons) {
    }
}
