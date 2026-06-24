package com.rca.analyzer.heuristics;

import com.rca.analyzer.config.ObservabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.CriticalPathTimeline;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.StructuredFindings;
import com.rca.common.model.Telemetry;
import com.rca.common.model.TimelineSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class FindingsBuilder {

    private final ObservabilityProperties observabilityProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FindingsBuilder(ObservabilityProperties observabilityProperties) {
        this.observabilityProperties = observabilityProperties;
    }

    public StructuredFindings build(KafkaHarMessage message, Telemetry telemetry, RcaHeuristicsResult heuristics) {
        StructuredFindings findings = new StructuredFindings();
        findings.setRequestId(telemetry.getRequestId());
        findings.setApi("%s / %s".formatted(
                nullToEmpty(telemetry.getHarApiKind()), nullToEmpty(telemetry.getHarApiName())));
        findings.setPodName(resolveDisplayPod(telemetry));
        findings.setPrimaryBottleneck(heuristics.getPrimaryCategory().label());
        findings.setCategoryScores(new LinkedHashMap<>(heuristics.getScores()));

        findings.setHarTimeline(buildHarTimeline(message, telemetry));
        findings.setBackendTimeline(buildBackendTimeline(telemetry));
        findings.setHarTimings(buildHarTimingsMap(message, telemetry));
        findings.setKibanaHighlights(summarizeKibana(telemetry));
        findings.setGraylogHighlights(summarizeGraylog(telemetry));
        findings.setGrafanaHighlights(summarizeGrafana(telemetry));
        findings.setObservabilitySources(buildObservabilitySources(telemetry));
        findings.setMetricComparisons(telemetry.getMetricComparisons() != null
                ? new ArrayList<>(telemetry.getMetricComparisons()) : new ArrayList<>());
        findings.setHarIssuesTriggered(heuristics.getHarIssuesTriggered() != null
                ? new ArrayList<>(heuristics.getHarIssuesTriggered()) : new ArrayList<>());
        findings.setDisambiguationNotes(heuristics.getDisambiguationNotes() != null
                ? new ArrayList<>(heuristics.getDisambiguationNotes()) : new ArrayList<>());
        findings.setConfidenceBreakdown(heuristics.getConfidenceBreakdown() != null
                ? new LinkedHashMap<>(heuristics.getConfidenceBreakdown()) : new LinkedHashMap<>());
        if (telemetry.getSlowHarEntries() != null && !telemetry.getSlowHarEntries().isEmpty()) {
            findings.setSlowHarEntries(new ArrayList<>(telemetry.getSlowHarEntries()));
            findings.setSlowHarSelection(telemetry.getSlowHarSelection());
        }
        if (telemetry.getHarForensicsFindings() != null && !telemetry.getHarForensicsFindings().isEmpty()) {
            findings.setHarForensicsFindings(new ArrayList<>(telemetry.getHarForensicsFindings()));
        }
        return findings;
    }

    private Map<String, String> buildObservabilitySources(Telemetry telemetry) {
        Map<String, String> sources = new LinkedHashMap<>();
        boolean kibanaHits = telemetry.getKibanaLogs() != null && !telemetry.getKibanaLogs().isEmpty();
        sources.put("kibana", observabilityProperties.kibanaConfigured()
                ? (kibanaHits ? "live" : "miss")
                : "disabled");
        sources.put("graylog", resolveGraylogSource(telemetry));
        sources.put("grafana", resolveGrafanaSource(telemetry));
        return sources;
    }

    private String resolveGraylogSource(Telemetry telemetry) {
        if (telemetry.getGraylogLogs() == null || telemetry.getGraylogLogs().isEmpty()) {
            return "miss";
        }
        if (telemetry.getGraylogLogs().stream().allMatch(l -> "stub".equals(l.get("source")))) {
            return "stub";
        }
        return "live";
    }

    private String resolveGrafanaSource(Telemetry telemetry) {
        Map<String, Object> metrics = telemetry.getPodMetrics();
        if (metrics == null || metrics.isEmpty()) {
            return "miss";
        }
        if ("stub".equals(metrics.get("source"))) {
            return "stub";
        }
        return "live";
    }

    public String toJson(StructuredFindings findings) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(findings);
        } catch (Exception e) {
            return findings.toString();
        }
    }

    private CriticalPathTimeline buildHarTimeline(KafkaHarMessage message, Telemetry telemetry) {
        CriticalPathTimeline timeline = new CriticalPathTimeline();
        timeline.setTotalMs(nz(message.getDurationMs()));

        addSegment(timeline, "Blocked", message.getBlockedMs(), "har", "A_NETWORK");
        addSegment(timeline, "DNS", message.getDnsMs(), "har", "A_NETWORK");
        addSegment(timeline, "Connect", message.getConnectMs(), "har", "A_NETWORK");
        addSegment(timeline, "SSL", message.getSslMs(), "har", "A_NETWORK");
        addSegment(timeline, "Send", message.getSendMs(), "har", null);
        addSegment(timeline, "TTFB (wait)", message.getWaitMs(), "har", "A_NETWORK");
        addSegment(timeline, "Receive", message.getReceiveMs(), "har", null);

        setDominant(timeline);
        return timeline;
    }

    private CriticalPathTimeline buildBackendTimeline(Telemetry telemetry) {
        CriticalPathTimeline timeline = new CriticalPathTimeline();
        if (telemetry.getKibanaLogs() == null || telemetry.getKibanaLogs().isEmpty()) {
            return timeline;
        }

        long network = maxLong(telemetry, "networkTimeMs");
        long wait = maxLong(telemetry, "totalWaitTimeMs");
        long exec = maxLong(telemetry, "totalExecTimeMs");
        long es = maxLong(telemetry, "esTimeMs");

        addSegment(timeline, "Network (Kibana)", network, "kibana", "A_NETWORK");
        addSegment(timeline, "Queue wait", wait, "kibana", "B_BACKEND_CPU");
        addSegment(timeline, "Execution", exec, "kibana", "B_BACKEND_CPU");
        addSegment(timeline, "ES query", es, "kibana", "C_DATABASE");

        timeline.setTotalMs(network + wait + exec + es);
        setDominant(timeline);
        return timeline;
    }

    private Map<String, Object> buildHarTimingsMap(KafkaHarMessage message, Telemetry telemetry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalMs", message.getDurationMs());
        map.put("waitMs", message.getWaitMs());
        map.put("dnsMs", message.getDnsMs());
        map.put("connectMs", message.getConnectMs());
        map.put("sslMs", message.getSslMs());
        map.put("sendMs", message.getSendMs());
        map.put("receiveMs", message.getReceiveMs());
        map.put("networkLegMs", message.getDnsMs() + message.getConnectMs() + message.getSslMs());
        map.put("waitSharePct", pct(message.getWaitMs(), message.getDurationMs()));
        if (telemetry.getSlowHarSelectedCount() > 0) {
            map.put("slowApiCount", telemetry.getSlowHarSelectedCount());
            map.put("slowApiSelection", telemetry.getSlowHarSelection());
        }
        return map;
    }

    private Map<String, Object> summarizeKibana(Telemetry telemetry) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (telemetry.getKibanaLogs() == null || telemetry.getKibanaLogs().isEmpty()) {
            map.put("hitCount", 0);
            map.put("live", false);
            return map;
        }
        map.put("live", true);
        map.put("hitCount", telemetry.getKibanaLogs().size());
        map.put("maxNetworkTimeMs", maxLong(telemetry, "networkTimeMs"));
        map.put("maxWaitTimeMs", maxLong(telemetry, "totalWaitTimeMs"));
        map.put("maxExecTimeMs", maxLong(telemetry, "totalExecTimeMs"));
        map.put("maxEsTimeMs", maxLong(telemetry, "esTimeMs"));
        map.put("errorCount", telemetry.getKibanaLogs().stream()
                .filter(l -> "ERROR".equalsIgnoreCase(String.valueOf(l.get("level"))))
                .count());
        map.put("gqlOperation", firstNonBlank(telemetry, "gqlOperation"));
        return map;
    }

    private Map<String, Object> summarizeGraylog(Telemetry telemetry) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (telemetry.getGraylogLogs() == null || telemetry.getGraylogLogs().isEmpty()) {
            return map;
        }
        if (telemetry.getGraylogLogs().stream().allMatch(l -> "stub".equals(l.get("source")))) {
            map.put("stub", true);
            return map;
        }
        long maxDb = 0, maxEs = 0, maxMongo = 0, maxPerf = 0, maxUpstream = 0;
        int ingressCount = 0, calltrackingCount = 0, perfstatsCount = 0;
        for (Map<String, Object> log : telemetry.getGraylogLogs()) {
            if ("stub".equals(log.get("source"))) {
                continue;
            }
            String kind = String.valueOf(log.getOrDefault("logKind", ""));
            switch (kind) {
                case "ingress" -> {
                    ingressCount++;
                    maxUpstream = Math.max(maxUpstream, longVal(log.get("upstreamResponseTimeMs")));
                }
                case "calltracking" -> {
                    calltrackingCount++;
                    maxDb = Math.max(maxDb, longVal(log.get("dbTimeTaken")));
                    maxEs = Math.max(maxEs, longVal(log.get("esTimeTaken")));
                    maxMongo = Math.max(maxMongo, longVal(log.get("mongoTimeTaken")));
                }
                case "perfstats" -> {
                    perfstatsCount++;
                    maxPerf = Math.max(maxPerf, longVal(log.get("timeTakenMs")));
                }
                default -> {
                    maxDb = Math.max(maxDb, longVal(log.get("dbTimeTaken")));
                    maxEs = Math.max(maxEs, longVal(log.get("esTimeTaken")));
                    maxMongo = Math.max(maxMongo, longVal(log.get("mongoTimeTaken")));
                }
            }
        }
        map.put("ingressCount", ingressCount);
        map.put("calltrackingCount", calltrackingCount);
        map.put("perfstatsCount", perfstatsCount);
        map.put("maxUpstreamMs", maxUpstream);
        map.put("maxDbTimeMs", maxDb);
        map.put("maxEsTimeMs", maxEs);
        map.put("maxMongoTimeMs", maxMongo);
        map.put("maxPerfstatsMs", maxPerf);
        return map;
    }

    private Map<String, Object> summarizeGrafana(Telemetry telemetry) {
        if (telemetry.getMetricComparisons() != null && !telemetry.getMetricComparisons().isEmpty()) {
            Map<String, Object> map = new LinkedHashMap<>();
            long elevated = telemetry.getMetricComparisons().stream().filter(c -> c.isTriggered()).count();
            map.put("comparisonCount", telemetry.getMetricComparisons().size());
            map.put("elevatedCount", elevated);
            telemetry.getMetricComparisons().stream()
                    .filter(c -> c.isTriggered())
                    .forEach(c -> map.put(c.getMetric(), Map.of(
                            "verdict", c.getVerdict(),
                            "ratio", c.getRatio(),
                            "interpretation", c.getInterpretation())));
            return map;
        }
        Map<String, Object> metrics = telemetry.getPodMetrics();
        if (metrics == null || metrics.isEmpty() || "stub".equals(metrics.get("source"))) {
            return metrics != null && "stub".equals(metrics.get("source"))
                    ? Map.of("stub", true) : Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cpuRate", metrics.get("container_cpu_usage_rate"));
        map.put("memoryRate", metrics.get("container_memory_usage_rate"));
        map.put("threadActive", metrics.get("thread_active"));
        map.put("threadRejected", metrics.get("thread_rejected"));
        map.put("threadQueue", metrics.get("thread_queue"));
        map.put("gcOldGenMs", metrics.get("gc_old_gen_ms"));
        if (metrics.containsKey("es_query_latency_ms")) {
            map.put("esHost", metrics.get("es_host"));
            map.put("esQueryLatencyMs", metrics.get("es_query_latency_ms"));
            map.put("esThreadRejected", metrics.get("es_threadpool_search_rejected"));
        }
        return map;
    }

    private void addSegment(CriticalPathTimeline timeline, String phase, long ms, String source, String categoryHint) {
        if (ms <= 0) {
            return;
        }
        timeline.getSegments().add(TimelineSegment.builder()
                .phase(phase)
                .durationMs(ms)
                .source(source)
                .categoryHint(categoryHint)
                .build());
    }

    private void setDominant(CriticalPathTimeline timeline) {
        timeline.getSegments().stream()
                .max((a, b) -> Long.compare(a.getDurationMs(), b.getDurationMs()))
                .ifPresent(seg -> {
                    timeline.setDominantPhase(seg.getPhase());
                    timeline.setDominantMs(seg.getDurationMs());
                });
    }

    private String resolveDisplayPod(Telemetry telemetry) {
        if (telemetry.getPodName() != null && !telemetry.getPodName().startsWith("rca-pod-")) {
            return telemetry.getPodName();
        }
        if (telemetry.getKibanaLogs() != null) {
            return telemetry.getKibanaLogs().stream()
                    .map(l -> (String) l.get("podName"))
                    .filter(p -> p != null && !p.isBlank())
                    .findFirst()
                    .orElse(telemetry.getPodName());
        }
        return telemetry.getPodName();
    }

    private long maxLong(Telemetry telemetry, String field) {
        return telemetry.getKibanaLogs().stream()
                .map(l -> l.get(field))
                .filter(Objects::nonNull)
                .mapToLong(this::longVal)
                .max()
                .orElse(0);
    }

    private String firstNonBlank(Telemetry telemetry, String field) {
        return telemetry.getKibanaLogs().stream()
                .map(l -> (String) l.get(field))
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
    }

    private static long nz(long v) {
        return Math.max(0, v);
    }

    private static double pct(long part, long total) {
        return total > 0 ? Math.round(1000.0 * part / total) / 10.0 : 0;
    }

    private long longVal(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
