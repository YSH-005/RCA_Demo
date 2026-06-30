package com.rca.analyzer.service;

import com.rca.analyzer.client.GrafanaClient;
import com.rca.analyzer.client.GrafanaIngressContext;
import com.rca.analyzer.client.GrafanaEsContext;
import com.rca.analyzer.client.GrafanaMongoContext;
import com.rca.analyzer.client.GrafanaPodContext;
import com.rca.analyzer.client.GrafanaQueryContext;
import com.rca.analyzer.client.GraylogClient;
import com.rca.analyzer.client.KibanaClient;
import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.analyzer.config.ObservabilityProperties;
import com.rca.common.observability.SessionCookieNormalizer;
import com.rca.analyzer.heuristics.GrafanaMetricEvaluator;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.error.ErrorContextExtractor;
import com.rca.common.error.ErrorContextMerger;
import com.rca.common.har.HarWaitAnalysisWeights;
import com.rca.common.model.ErrorContext;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.SlowHarEntry;
import com.rca.common.model.Telemetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public Telemetry collect(String requestId, Instant from, Instant to, HarStitchContext har, Instant eventTime) {
        return collect(requestId, from, to, har, eventTime, null);
    }

    public Telemetry collect(
            String requestId, Instant from, Instant to,
            HarStitchContext har, Instant eventTime, ErrorContext harError) {
        return collect(requestId, from, to, har, eventTime, harError, SessionCookies.empty());
    }

    public Telemetry collect(
            String requestId, Instant from, Instant to,
            HarStitchContext har, Instant eventTime, ErrorContext harError,
            SessionCookies sessionCookies) {

        Telemetry telemetry = new Telemetry();
        telemetry.setQueryWindowStart(from.toString());
        telemetry.setQueryWindowEnd(to.toString());
        if (eventTime != null) {
            telemetry.setEventTimestamp(eventTime.toString());
        }

        List<Map<String, Object>> kibanaLogs = fetchKibanaLogs(requestId, from, to, harError);
        telemetry.setKibanaLogs(kibanaLogs);

        ErrorContext errorContext = ErrorContextMerger.merge(harError, kibanaLogs);
        telemetry.setErrorContext(errorContext);

        List<Map<String, Object>> graylogLogs = fetchGraylogLogs(
                requestId, from, to, har, kibanaLogs, sessionCookies);
        telemetry.setGraylogLogs(graylogLogs);

        String podName = resolvePodName(graylogLogs, kibanaLogs);
        telemetry.setPodName(podName);

        Instant incidentAnchor = eventTime != null ? eventTime : from;
        GrafanaBundle grafana = fetchGrafanaBundle(
                podName, from, to, incidentAnchor, kibanaLogs, graylogLogs, errorContext, sessionCookies);
        telemetry.setPodMetrics(grafana.podMetrics());
        telemetry.setMetricComparisons(grafana.comparisons());

        telemetry.setLogLines(flattenLogs(graylogLogs, kibanaLogs, grafana.podMetrics()));
        return telemetry;
    }

    /**
     * Collects Kibana, Graylog, and Grafana for every selected slow API (deduped by requestId),
     * tagging logs/metrics with wait-time analysis weights.
     */
    public Telemetry collectForMessage(KafkaHarMessage message, ErrorContext harError) {
        SessionCookies sessionCookies = resolveSessionCookies(message);
        List<SlowHarEntry> slowEntries = resolveSlowEntries(message);
        Map<String, Double> weights = HarWaitAnalysisWeights.byApiKey(slowEntries);

        Instant defaultFrom = parseInstant(message.getQueryWindowFrom(), Instant.now().minusSeconds(450));
        Instant defaultTo = parseInstant(message.getQueryWindowTo(), Instant.now());
        Instant defaultEvent = parseInstant(message.getEventTimestamp(), defaultFrom);

        Telemetry telemetry = new Telemetry();
        telemetry.setQueryWindowStart(defaultFrom.toString());
        telemetry.setQueryWindowEnd(defaultTo.toString());
        telemetry.setEventTimestamp(defaultEvent.toString());
        telemetry.setRequestId(message.getCorrelationId());
        telemetry.setSlowHarEntries(new ArrayList<>(slowEntries));
        telemetry.setSlowHarSelectedCount(slowEntries.size());
        telemetry.setSlowHarSelection(message.getSlowEntrySelection());
        telemetry.setSlowApiAnalysisWeights(new LinkedHashMap<>(weights));

        List<Map<String, Object>> allKibana = new ArrayList<>();
        List<Map<String, Object>> allGraylog = new ArrayList<>();
        List<MetricComparison> allComparisons = new ArrayList<>();
        Map<String, Object> mergedMetrics = new LinkedHashMap<>();
        Set<String> fetchedRequestIds = new HashSet<>();

        for (SlowHarEntry entry : slowEntries) {
            String requestId = effectiveRequestId(entry, message.getCorrelationId());
            double weight = entry.getAnalysisWeight() > 0
                    ? entry.getAnalysisWeight()
                    : weights.getOrDefault(HarWaitAnalysisWeights.apiKey(entry), 0.0);

            if (requestId.isBlank()) {
                log.debug("Skipping observability fetch for slow API {} — no requestId",
                        slowApiLabel(entry));
                continue;
            }
            if (!fetchedRequestIds.add(requestId)) {
                continue;
            }

            Instant from = parseInstant(entry.getQueryWindowFrom(), defaultFrom);
            Instant to = parseInstant(entry.getQueryWindowTo(), defaultTo);
            Instant eventTime = parseInstant(entry.getEventTimestamp(), defaultEvent);
            HarStitchContext har = HarStitchContext.from(entry);

            List<Map<String, Object>> kibanaLogs = fetchKibanaLogs(requestId, from, to, harError);
            tagObservabilityLogs(kibanaLogs, entry, requestId, weight);
            kibanaLogs.forEach(log -> mergeKibanaLog(allKibana, log));

            List<Map<String, Object>> graylogLogs = fetchGraylogLogs(
                    requestId, from, to, har, kibanaLogs, sessionCookies);
            tagObservabilityLogs(graylogLogs, entry, requestId, weight);
            allGraylog.addAll(graylogLogs);

            String podName = resolvePodName(graylogLogs, kibanaLogs);
            ErrorContext errorContext = ErrorContextMerger.merge(harError, allKibana);
            GrafanaBundle grafana = fetchGrafanaBundle(
                    podName, from, to, eventTime, kibanaLogs, graylogLogs, errorContext, sessionCookies);
            stampComparisonWeights(grafana.comparisons(), weight, requestId);
            allComparisons.addAll(grafana.comparisons());
            mergePodMetrics(mergedMetrics, grafana.podMetrics(), weight);

            log.info("Observability for slow API {} requestId={} weight={} kibana={} graylog={} grafanaMetrics={}",
                    slowApiLabel(entry), requestId, String.format("%.3f", weight),
                    kibanaLogs.size(), graylogLogs.size(), grafana.comparisons().size());
        }

        if (fetchedRequestIds.isEmpty()) {
            log.info("No requestIds on slow entries — falling back to primary correlationId={}",
                    message.getCorrelationId());
            return collect(
                    message.getCorrelationId(), defaultFrom, defaultTo,
                    HarStitchContext.from(message), defaultEvent, harError, sessionCookies);
        }

        telemetry.setKibanaLogs(allKibana);
        telemetry.setGraylogLogs(allGraylog);
        telemetry.setErrorContext(ErrorContextMerger.merge(harError, allKibana));
        telemetry.setPodName(resolvePodName(allGraylog, allKibana));
        telemetry.setMetricComparisons(allComparisons);
        if (!mergedMetrics.isEmpty()) {
            mergedMetrics.putIfAbsent("source", "grafana");
            mergedMetrics.putIfAbsent("podName", telemetry.getPodName());
            telemetry.setPodMetrics(mergedMetrics);
        } else if (!allComparisons.isEmpty()) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("source", "grafana");
            metrics.put("podName", telemetry.getPodName());
            metrics.putAll(grafanaMetricEvaluator.flattenForLegacyMetrics(allComparisons));
            telemetry.setPodMetrics(metrics);
        }
        telemetry.setLogLines(flattenLogs(allGraylog, allKibana, telemetry.getPodMetrics()));
        return telemetry;
    }

    private List<SlowHarEntry> resolveSlowEntries(KafkaHarMessage message) {
        if (message.getSlowEntries() != null && !message.getSlowEntries().isEmpty()) {
            return new ArrayList<>(message.getSlowEntries());
        }
        return List.of(SlowHarEntry.builder()
                .requestId(message.getCorrelationId())
                .url(message.getSlowestUrl())
                .method(message.getSlowestMethod())
                .apiKind(message.getApiKind())
                .apiName(message.getApiName())
                .durationMs(message.getDurationMs())
                .waitMs(message.getWaitMs())
                .receiveMs(message.getReceiveMs())
                .sendMs(message.getSendMs())
                .responseStatus(message.getResponseStatus())
                .eventTimestamp(message.getEventTimestamp())
                .queryWindowFrom(message.getQueryWindowFrom())
                .queryWindowTo(message.getQueryWindowTo())
                .build());
    }

    private static String effectiveRequestId(SlowHarEntry entry, String fallback) {
        if (entry.getRequestId() != null && !entry.getRequestId().isBlank()) {
            return entry.getRequestId().trim();
        }
        return fallback != null ? fallback.trim() : "";
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void tagObservabilityLogs(
            List<Map<String, Object>> logs, SlowHarEntry entry, String requestId, double weight) {
        String label = slowApiLabel(entry);
        for (Map<String, Object> log : logs) {
            log.put("slowApiLabel", label);
            log.put("analysisWeight", weight);
            log.put("correlationRequestId", requestId);
        }
    }

    private static void stampComparisonWeights(List<MetricComparison> comparisons, double weight, String requestId) {
        for (MetricComparison comparison : comparisons) {
            comparison.setAnalysisWeight(weight);
            comparison.setRequestId(requestId);
        }
    }

    private static void mergePodMetrics(Map<String, Object> target, Map<String, Object> source, double weight) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("source".equals(key)) {
                if ("grafana".equals(String.valueOf(entry.getValue()))) {
                    target.put(key, entry.getValue());
                } else {
                    target.putIfAbsent(key, entry.getValue());
                }
                continue;
            }
            if ("podName".equals(key) || key.endsWith("WindowStart") || key.endsWith("WindowEnd")) {
                target.putIfAbsent(key, entry.getValue());
                continue;
            }
            if (entry.getValue() instanceof Number number) {
                double weighted = number.doubleValue() * weight;
                target.merge(key, weighted, (a, b) -> Math.max(((Number) a).doubleValue(), ((Number) b).doubleValue()));
            }
        }
    }

    private static String slowApiLabel(SlowHarEntry entry) {
        if (entry.getApiKind() != null && !entry.getApiKind().isBlank()) {
            return entry.getApiKind() + "/" + nullToEmpty(entry.getApiName());
        }
        return nullToEmpty(entry.getApiName());
    }

    private GrafanaBundle fetchGrafanaBundle(
            String podName, Instant incidentFrom, Instant incidentTo, Instant eventTime,
            List<Map<String, Object>> kibanaLogs, List<Map<String, Object>> graylogLogs,
            ErrorContext errorContext, SessionCookies sessionCookies) {

        if (useGrafanaStub(sessionCookies)) {
            log.debug("Grafana provider: synthetic for pod={}", podName);
            SyntheticGrafanaProvider.GrafanaResult result =
                    SyntheticGrafanaProvider.build(podName, kibanaLogs, graylogLogs);
            return new GrafanaBundle(result.podMetrics(), result.comparisons());
        }

        int baselineHours = heuristicsProperties.getGrafana().getBaselineHours();
        int gapMinutes = heuristicsProperties.getGrafana().getBaselineGapMinutes();
        Instant baselineTo = incidentFrom.minus(gapMinutes, ChronoUnit.MINUTES);
        Instant baselineFrom = baselineTo.minus(baselineHours, ChronoUnit.HOURS);

        List<String> esHosts = GrafanaEsContext.resolveHostsFromLogs(kibanaLogs, graylogLogs);
        List<String> mongoHosts = GrafanaMongoContext.resolveHostsFromLogs(graylogLogs);
        GrafanaQueryContext baseContext = grafanaContext(podName, kibanaLogs, graylogLogs, "", "");
        GrafanaClient.InfluxScope podScope = (mongoHosts.isEmpty() && esHosts.isEmpty())
                ? GrafanaClient.InfluxScope.ALL
                : GrafanaClient.InfluxScope.POD_ONLY;

        GrafanaClient.GrafanaFetchResult incidentResult = grafanaClient.fetchTimeSeriesResult(
                baseContext, incidentFrom, incidentTo, sessionCookies.grafana(), podScope);
        if (!incidentResult.authenticated()) {
            if (properties.isUseSynthetic()) {
                log.warn("Grafana auth failed for pod={} — using synthetic grafana", podName);
                SyntheticGrafanaProvider.GrafanaResult result =
                        SyntheticGrafanaProvider.build(podName, kibanaLogs, graylogLogs);
                return new GrafanaBundle(result.podMetrics(), result.comparisons());
            }
            log.warn("Grafana auth failed for pod={} — synthetic disabled, skipping grafana metrics", podName);
            return new GrafanaBundle(Map.of(), List.of());
        }

        Map<String, List<Double>> incidentSeries = new LinkedHashMap<>(incidentResult.series());
        Map<String, List<Double>> baselineSeries = new LinkedHashMap<>(
                grafanaClient.fetchTimeSeries(baseContext, baselineFrom, baselineTo,
                        sessionCookies.grafana(), podScope));

        List<MetricComparison> comparisons = new ArrayList<>(
                grafanaMetricEvaluator.evaluate(incidentSeries, baselineSeries, podName));

        for (String esHost : esHosts) {
            GrafanaQueryContext esContext = baseContext.withEsHost(esHost);
            Map<String, List<Double>> esIncident = grafanaClient.fetchTimeSeries(
                    esContext, incidentFrom, incidentTo, sessionCookies.grafana(),
                    GrafanaClient.InfluxScope.ES_ONLY);
            Map<String, List<Double>> esBaseline = grafanaClient.fetchTimeSeries(
                    esContext, baselineFrom, baselineTo, sessionCookies.grafana(),
                    GrafanaClient.InfluxScope.ES_ONLY);
            mergeTimeSeries(incidentSeries, esIncident);
            mergeTimeSeries(baselineSeries, esBaseline);
            comparisons.addAll(grafanaMetricEvaluator.evaluate(
                    esIncident, esBaseline, esHost, "es_"));
            log.info("Grafana ES metrics for host={} incidentSeries={}", esHost, esIncident.size());
        }

        for (String mongoHost : mongoHosts) {
            GrafanaQueryContext mongoContext = baseContext.withMongoHost(mongoHost);
            Map<String, List<Double>> mongoIncident = grafanaClient.fetchTimeSeries(
                    mongoContext, incidentFrom, incidentTo, sessionCookies.grafana(),
                    GrafanaClient.InfluxScope.MONGO_ONLY);
            Map<String, List<Double>> mongoBaseline = grafanaClient.fetchTimeSeries(
                    mongoContext, baselineFrom, baselineTo, sessionCookies.grafana(),
                    GrafanaClient.InfluxScope.MONGO_ONLY);
            mergeTimeSeries(incidentSeries, mongoIncident);
            mergeTimeSeries(baselineSeries, mongoBaseline);
            comparisons.addAll(grafanaMetricEvaluator.evaluate(
                    mongoIncident, mongoBaseline, mongoHost, "mongo_"));
            log.info("Grafana mongo metrics for host={} incidentSeries={}", mongoHost, mongoIncident.size());
        }

        if (incidentSeries.isEmpty()) {
            log.warn("Grafana auth OK but no incident series for pod={} esHosts={} mongoHosts={} — not using synthetic",
                    podName, esHosts, mongoHosts);
            return new GrafanaBundle(Map.of(), List.of());
        }

        if (errorContext != null && !errorContext.isInfraMetricsRelevant()) {
            comparisons = markInfraMetricsInapplicable(comparisons, errorContext);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("source", "grafana");
        metrics.put("podName", podName);
        if (!esHosts.isEmpty()) {
            metrics.put("esHosts", esHosts);
        }
        if (!mongoHosts.isEmpty()) {
            metrics.put("mongoHosts", mongoHosts);
        }
        metrics.putAll(grafanaMetricEvaluator.flattenForLegacyMetrics(comparisons));
        metrics.put("baselineWindowStart", baselineFrom.toString());
        metrics.put("baselineWindowEnd", baselineTo.toString());
        return new GrafanaBundle(metrics, comparisons);
    }

    private static void mergeTimeSeries(Map<String, List<Double>> target, Map<String, List<Double>> source) {
        source.forEach((name, values) -> target.merge(name, values, (a, b) -> {
            a.addAll(b);
            return a;
        }));
    }

    private GrafanaQueryContext grafanaContext(
            String podName, List<Map<String, Object>> kibanaLogs, List<Map<String, Object>> graylogLogs,
            String esHost, String mongoHost) {
        var grafana = properties.getGrafana();
        GrafanaIngressContext.Resolved ingress = GrafanaIngressContext.resolve(
                graylogLogs,
                grafana.getIngressNamespace(),
                grafana.getIngressControllerClass());
        if (ingress.controllerPod().isBlank()) {
            log.debug("Ingress controller not found in Graylog — ingress Grafana metrics will be skipped");
        }
        String namespace = GrafanaPodContext.resolveNamespace(
                graylogLogs, kibanaLogs, grafana.getK8sNamespace());
        return GrafanaQueryContext.of(
                podName,
                namespace,
                esHost,
                mongoHost,
                ingress);
    }

    private List<Map<String, Object>> fetchKibanaLogs(String requestId, Instant from, Instant to,
                                                      ErrorContext harError) {
        List<Map<String, Object>> kibanaLogs = new ArrayList<>(kibanaClient.fetchMonitoringLogs(requestId, from, to));
        if (properties.useSeparateKibanaErrorIndex()) {
            kibanaClient.fetchErrorLogs(requestId, from, to).forEach(log -> mergeKibanaLog(kibanaLogs, log));
        }

        String supportReference = harError != null ? nullToEmpty(harError.getSupportReference()) : "";
        if (!supportReference.isBlank() && !kibanaHasSupportReference(kibanaLogs, supportReference)) {
            log.info("Kibana miss for supportReference={} — fetching error index by reference", supportReference);
            kibanaClient.fetchBySupportReference(supportReference, from, to)
                    .forEach(log -> mergeKibanaLog(kibanaLogs, log));
        }
        return kibanaLogs;
    }

    private static void mergeKibanaLog(List<Map<String, Object>> kibanaLogs, Map<String, Object> log) {
        Object logId = log.get("id");
        if (logId == null || kibanaLogs.stream().noneMatch(existing -> logId.equals(existing.get("id")))) {
            kibanaLogs.add(log);
        }
    }

    private static boolean kibanaHasSupportReference(List<Map<String, Object>> logs, String supportReference) {
        return logs.stream().anyMatch(log -> supportReference.equals(log.get("supportReference"))
                || String.valueOf(log.getOrDefault("attributesMsg", "")).contains(supportReference));
    }

    private static List<MetricComparison> markInfraMetricsInapplicable(
            List<MetricComparison> comparisons, ErrorContext errorContext) {
        String note = "Infra metric not scored — %s (%s). %s".formatted(
                errorContext.getExceptionKind() != null ? errorContext.getExceptionKind() : "application error",
                ErrorContextExtractor.firstNonBlank(errorContext.getExceptionType(), "exception"),
                truncateReason(errorContext.getClassificationReason()));
        return comparisons.stream()
                .map(c -> MetricComparison.builder()
                        .source(c.getSource())
                        .metric(c.getMetric())
                        .host(c.getHost())
                        .incident(c.getIncident())
                        .baseline(c.getBaseline())
                        .ratio(c.getRatio())
                        .delta(c.getDelta())
                        .verdict("not_applicable")
                        .formula(c.getFormula())
                        .triggered(false)
                        .strength(0)
                        .interpretation(note)
                        .build())
                .toList();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return reason.length() <= 120 ? reason : reason.substring(0, 120) + "...";
    }

    private List<Map<String, Object>> fetchKibanaLogs(String requestId, Instant from, Instant to) {
        return fetchKibanaLogs(requestId, from, to, null);
    }

    private List<Map<String, Object>> fetchGraylogLogs(
            String requestId, Instant from, Instant to,
            HarStitchContext har, List<Map<String, Object>> kibanaLogs,
            SessionCookies sessionCookies) {

        if (useGraylogStub(sessionCookies)) {
            log.debug("Graylog provider: synthetic for requestId={}", requestId);
            return SyntheticGraylogProvider.build(requestId, har, kibanaLogs);
        }

        GraylogClient.GraylogFetchResult result =
                graylogClient.fetchLogsResult(requestId, from, to, sessionCookies.graylog());
        if (!result.authenticated()) {
            if (properties.isUseSynthetic()) {
                log.warn("Graylog auth failed for requestId={} — using synthetic graylog", requestId);
                return SyntheticGraylogProvider.build(requestId, har, kibanaLogs);
            }
            log.warn("Graylog auth failed for requestId={} — synthetic disabled, skipping graylog", requestId);
            return List.of();
        }
        if (result.logs().isEmpty()) {
            log.warn("Graylog auth OK but no logs for requestId={} (total_results={}) — not using synthetic",
                    requestId, result.totalResults());
            return List.of();
        }
        return result.logs();
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
        if (podMetrics != null && !podMetrics.isEmpty()) {
            lines.add("[grafana] " + podMetrics);
        }
        return lines;
    }

    private record GrafanaBundle(Map<String, Object> podMetrics, List<MetricComparison> comparisons) {
    }

    private record SessionCookies(String graylog, String grafana) {
        static SessionCookies empty() {
            return new SessionCookies("", "");
        }
    }

    private SessionCookies resolveSessionCookies(KafkaHarMessage message) {
        String uploadGraylog = SessionCookieNormalizer.graylog(message.getGraylogSessionCookie());
        String uploadGrafana = SessionCookieNormalizer.grafana(message.getGrafanaSessionCookie());
        String graylog = !uploadGraylog.isBlank()
                ? uploadGraylog
                : SessionCookieNormalizer.graylog(properties.getGraylog().getSessionCookie());
        String grafana = !uploadGrafana.isBlank()
                ? uploadGrafana
                : SessionCookieNormalizer.grafana(properties.getGrafana().getSessionCookie());

        if (!uploadGraylog.isBlank() || !uploadGrafana.isBlank()) {
            log.info("Using per-job session cookies from upload graylogLen={} grafanaLen={}",
                    uploadGraylog.length(), uploadGrafana.length());
        } else if (graylog.isBlank() && grafana.isBlank()) {
            log.warn("No Graylog/Grafana session cookies on upload or in .env — auth may fail until cookies are pasted");
        }
        return new SessionCookies(graylog, grafana);
    }

    private boolean useGraylogStub(SessionCookies sessionCookies) {
        if (!properties.isUseSynthetic()) {
            return false;
        }
        if (properties.getGraylog().isStub()) {
            return true;
        }
        if (!sessionCookies.graylog().isBlank()) {
            return false;
        }
        return properties.useGraylogStub();
    }

    private boolean useGrafanaStub(SessionCookies sessionCookies) {
        if (!properties.isUseSynthetic()) {
            return false;
        }
        if (properties.getGrafana().isStub()) {
            return true;
        }
        if (!sessionCookies.grafana().isBlank()) {
            return false;
        }
        return properties.useGrafanaStub();
    }
}
