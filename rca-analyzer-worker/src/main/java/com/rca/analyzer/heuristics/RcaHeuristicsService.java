package com.rca.analyzer.heuristics;

import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.common.enums.BottleneckCategory;
import com.rca.common.enums.FaultType;
import com.rca.common.model.HeuristicRuleHit;
import com.rca.common.model.HarForensicsFinding;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.SlowHarEntry;
import com.rca.common.model.RcaReport;
import com.rca.common.model.StructuredFindings;
import com.rca.common.model.Telemetry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RcaHeuristicsService {

    private final HeuristicsProperties properties;
    private final GrafanaMetricEvaluator grafanaMetricEvaluator;

    public RcaHeuristicsResult analyze(Telemetry telemetry) {
        RcaHeuristicsResult result = new RcaHeuristicsResult();
        result.setScores(new EnumMap<>(BottleneckCategory.class));
        for (BottleneckCategory c : BottleneckCategory.values()) {
            if (c != BottleneckCategory.UNKNOWN) {
                result.getScores().put(c, 0.0);
            }
        }

        List<HeuristicRuleHit> hits = new ArrayList<>();
        List<String> harIssues = new ArrayList<>();
        hits.addAll(networkRules(telemetry, harIssues));
        hits.addAll(backendRules(telemetry));
        hits.addAll(databaseRules(telemetry));
        hits.addAll(exceptionRules(telemetry));
        hits.addAll(grafanaComparisonRules(telemetry));

        result.setRuleHits(hits);
        result.setHarIssuesTriggered(harIssues);

        for (HeuristicRuleHit hit : hits) {
            if (hit.isTriggered() && !hit.isStubSource()) {
                result.getScores().merge(hit.getCategory(), hit.getScoreContribution(), Double::sum);
            }
        }

        BottleneckCategory primary = pickPrimary(result.getScores());
        result.setPrimaryCategory(primary);

        ConfidenceCalculator.SourceCounts sources = ConfidenceCalculator.countSources(telemetry);
        result.setSourcesAvailable(sources.available());
        result.setSourceAgreement(sources.agreeing());
        result.setDisambiguationNotes(DisambiguationEngine.analyze(telemetry, result));
        result.setConfidence(ConfidenceCalculator.compute(
                result.getScores(), primary, sources.agreeing(), sources.available(),
                properties.getMinConfidenceScore()));
        result.setConfidenceBreakdown(ConfidenceCalculator.breakdown(
                result, primary, sources.agreeing(), sources.available()));
        mergeHarForensicsIssues(result, telemetry);
        return result;
    }

    private void mergeHarForensicsIssues(RcaHeuristicsResult result, Telemetry telemetry) {
        if (telemetry.getHarForensicsFindings() == null) {
            return;
        }
        for (HarForensicsFinding finding : telemetry.getHarForensicsFindings()) {
            if (finding.isTriggered() && !result.getHarIssuesTriggered().contains(finding.getIssueId())) {
                result.getHarIssuesTriggered().add(finding.getIssueId());
            }
        }
    }

    public RcaReport toReport(RcaHeuristicsResult heuristics, StructuredFindings findings,
                              String summary, List<String> recommendedActions) {
        RcaReport report = new RcaReport();
        report.setBottleneckCategory(heuristics.getPrimaryCategory());
        report.setCategoryScores(heuristics.getScores());
        report.setRuleHits(heuristics.getRuleHits());
        report.setFaultClassification(mapFault(heuristics.getPrimaryCategory()));
        report.setConfidenceScore(heuristics.getConfidence());
        report.setStructuredFindings(findings);
        if (findings != null) {
            report.setHarTimeline(findings.getHarTimeline());
            report.setBackendTimeline(findings.getBackendTimeline());
        }
        report.setSummary(summary);
        List<String> evidence = new ArrayList<>(heuristics.getRuleHits().stream()
                .filter(h -> h.isTriggered() && !h.isStubSource())
                .map(HeuristicRuleHit::getEvidence)
                .toList());
        if (findings != null && findings.getHarForensicsFindings() != null) {
            for (HarForensicsFinding f : findings.getHarForensicsFindings()) {
                evidence.add(f.getIssueId() + " — " + f.getEvidence());
            }
        }
        report.setEvidence(evidence);
        List<String> actions = recommendedActions != null && !recommendedActions.isEmpty()
                ? new ArrayList<>(recommendedActions)
                : new ArrayList<>(defaultActions(heuristics, findings));
        if (findings != null && findings.getHarForensicsFindings() != null) {
            for (HarForensicsFinding f : findings.getHarForensicsFindings()) {
                if (f.getRecommendation() != null && !f.getRecommendation().isBlank()
                        && !actions.contains(f.getRecommendation())) {
                    actions.add(f.getRecommendation());
                }
            }
        }
        report.setRecommendedActions(actions);
        return report;
    }

    // ── (A) Network ─────────────────────────────────────────────────────────

    private List<HeuristicRuleHit> networkRules(Telemetry t, List<String> harIssues) {
        List<HeuristicRuleHit> hits = new ArrayList<>();

        for (HarTimingView view : harTimingViews(t)) {
            String suffix = view.suffix();
            long total = view.durationMs();
            long networkLeg = view.dnsMs() + view.connectMs() + view.sslMs();
            long wait = view.waitMs();
            long blocked = view.blockedMs();
            long receive = view.receiveMs();
            long send = view.sendMs();

            long dns = view.dnsMs();
            long connect = view.connectMs();
            long ssl = view.sslMs();

            if (dns > 0 && total > 0) {
                hits.add(trackHarIssue(harIssues, "H02_dns_slow", evaluate("har_dns_ms" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD, dns,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarDnsMs()),
                        0.7, false,
                        "HAR [%s] dns=%dms exceeds threshold %dms".formatted(
                                view.label(), dns, properties.getThresholds().getHarDnsMs()))));
                hits.add(trackHarIssue(harIssues, "H02_dns_slow", evaluate("har_dns_share" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.SHARE_OF_TOTAL, dns,
                        StatEvaluator.EvalContext.share(total, properties.getThresholds().getHarDnsShare()),
                        0.65, false,
                        "HAR [%s] dns=%dms is %.0f%% of total %dms".formatted(
                                view.label(), dns, pct(dns, total), total))));
            }

            if (connect >= properties.getThresholds().getHarConnectMs()) {
                hits.add(trackHarIssue(harIssues, "H03_connect_slow", evaluate("har_connect_ms" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD, connect,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarConnectMs()),
                        0.75, false,
                        "HAR [%s] connect=%dms exceeds threshold %dms".formatted(
                                view.label(), connect, properties.getThresholds().getHarConnectMs()))));
            }

            if (ssl >= properties.getThresholds().getHarSslMs()) {
                hits.add(trackHarIssue(harIssues, "H04_tls_slow", evaluate("har_ssl_ms" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD, ssl,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarSslMs()),
                        0.75, false,
                        "HAR [%s] ssl=%dms exceeds threshold %dms".formatted(
                                view.label(), ssl, properties.getThresholds().getHarSslMs()))));
            }

            if (total >= properties.getThresholds().getHarSlowApiMs()) {
                hits.add(trackHarIssue(harIssues, "H09_slow_api", evaluate("har_total_duration" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD, total,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarSlowApiMs()),
                        0.55, false,
                        "HAR [%s] total duration=%dms exceeds threshold %dms".formatted(
                                view.label(), total, properties.getThresholds().getHarSlowApiMs()))));
            }

            if (total > 0 && blocked > 0) {
                hits.add(trackHarIssue(harIssues, "H01_browser_queuing", evaluate("har_blocked_share" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.SHARE_OF_TOTAL, blocked,
                        StatEvaluator.EvalContext.share(total, properties.getThresholds().getHarBlockedShare()),
                        0.6, false,
                        "HAR [%s] blocked/queuing=%dms is %.0f%% of total %dms".formatted(
                                view.label(), blocked, pct(blocked, total), total))));
            }

            hits.add(trackHarIssue(harIssues, "H05_network_leg", evaluate("har_network_share" + suffix,
                    BottleneckCategory.A_NETWORK, StatFormula.SHARE_OF_TOTAL,
                    networkLeg, StatEvaluator.EvalContext.share(total, properties.getThresholds().getHarNetworkShare()),
                    1.0, false,
                    "HAR [%s] network leg=%dms is %.0f%% of total %dms".formatted(
                            view.label(), networkLeg, pct(networkLeg, total), total))));

            hits.add(trackHarIssue(harIssues, "H06_high_ttfb", evaluate("har_wait_share" + suffix,
                    BottleneckCategory.A_NETWORK, StatFormula.SHARE_OF_TOTAL,
                    wait, StatEvaluator.EvalContext.share(total, properties.getThresholds().getHarWaitShare()),
                    0.9, false,
                    "HAR [%s] TTFB wait=%dms is %.0f%% of total %dms".formatted(
                            view.label(), wait, pct(wait, total), total))));

            hits.add(trackHarIssue(harIssues, "H06_high_ttfb", evaluate("har_wait_ms" + suffix,
                    BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD,
                    wait, StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarWaitMs()),
                    0.8, false,
                    "HAR [%s] wait=%dms exceeds threshold %dms".formatted(
                            view.label(), wait, properties.getThresholds().getHarWaitMs()))));

            if (total > 0 && receive > 0) {
                hits.add(trackHarIssue(harIssues, "H07_slow_download", evaluate("har_receive_share" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.SHARE_OF_TOTAL, receive,
                        StatEvaluator.EvalContext.share(total, properties.getThresholds().getHarReceiveShare()),
                        0.7, false,
                        "HAR [%s] content download=%dms is %.0f%% of total %dms".formatted(
                                view.label(), receive, pct(receive, total), total))));
            }

            if (send >= properties.getThresholds().getHarSendMs()) {
                hits.add(trackHarIssue(harIssues, "H08_slow_upload", evaluate("har_send_ms" + suffix,
                        BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD, send,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarSendMs()),
                        0.5, false,
                        "HAR [%s] send=%dms exceeds threshold %dms".formatted(
                                view.label(), send, properties.getThresholds().getHarSendMs()))));
            }

            if (view.responseStatus() >= 400) {
                harIssues.add("H10_http_error");
                hits.add(evaluate("har_http_error" + suffix, BottleneckCategory.D_EXCEPTION, StatFormula.PRESENT,
                        1, StatEvaluator.EvalContext.present(), 0.9, false,
                        "HAR [%s] HTTP status=%d".formatted(view.label(), view.responseStatus())));
            }
        }

        long wait = nz(t.getHarWaitMs());
        long explained = maxKibana(t, "totalWaitTimeMs") + maxKibana(t, "totalExecTimeMs") + maxKibana(t, "esTimeMs");
        long gap = wait - explained;
        if (wait > 500 && gap > properties.getThresholds().getHarBackendGapMs()) {
            harIssues.add("H11_har_backend_gap");
            hits.add(evaluate("har_backend_gap", BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD,
                    gap, StatEvaluator.EvalContext.threshold(properties.getThresholds().getHarBackendGapMs()),
                    0.65, false,
                    "HAR wait=%dms minus Kibana backend=%dms leaves %dms unaccounted".formatted(wait, explained, gap)));
        } else if (wait > 500 && explained > 0 && gap < 200) {
            harIssues.add("H12_wait_explained_by_backend");
        }

        List<Double> kibanaNetwork = kibanaLongs(t, "networkTimeMs");
        if (!kibanaNetwork.isEmpty()) {
            double maxNetwork = kibanaNetwork.stream().mapToDouble(d -> d).max().orElse(0);
            hits.add(evaluate("kibana_network_time", BottleneckCategory.A_NETWORK, StatFormula.THRESHOLD,
                    maxNetwork, StatEvaluator.EvalContext.threshold(properties.getThresholds().getKibanaNetworkTimeMs()),
                    0.85, false,
                    "Kibana REQUEST_RECEIVED_NETWORK_TIME max=%.0fms".formatted(maxNetwork)));

            if (kibanaNetwork.size() >= 2) {
                double latest = kibanaNetwork.get(0);
                hits.add(evaluate("kibana_network_median_deviation", BottleneckCategory.A_NETWORK,
                        StatFormula.MEDIAN_DEVIATION, latest,
                        StatEvaluator.EvalContext.medianDeviation(kibanaNetwork,
                                properties.getThresholds().getLogTimingDeviation()),
                        0.7, false,
                        "Kibana network time %.0fms deviates from median %.0fms across %d logs".formatted(
                                latest, StatEvaluator.median(kibanaNetwork), kibanaNetwork.size())));
            }
        }

        if (t.getGraylogLogs() != null) {
            for (Map<String, Object> log : t.getGraylogLogs()) {
                if (!"ingress".equals(log.get("logKind"))) {
                    continue;
                }
                long requestMs = longVal(log.get("requestTimeMs"));
                long upstreamMs = longVal(log.get("upstreamResponseTimeMs"));
                if (requestMs <= 0) {
                    continue;
                }
                hits.add(evaluate("graylog_ingress_upstream_share", BottleneckCategory.A_NETWORK,
                        StatFormula.SHARE_OF_TOTAL, upstreamMs,
                        StatEvaluator.EvalContext.share(requestMs, 0.80),
                        0.75, isStub(log),
                        "Graylog ingress upstream=%dms is %.0f%% of requestTime %dms".formatted(
                                upstreamMs, pct(upstreamMs, requestMs), requestMs)));
            }
        }
        return hits;
    }

    // ── (B) Backend / CPU ───────────────────────────────────────────────────

    private List<HeuristicRuleHit> backendRules(Telemetry t) {
        List<HeuristicRuleHit> hits = new ArrayList<>();
        Map<String, Object> metrics = t.getPodMetrics();
        boolean stubMetrics = isStub(metrics);

        // Legacy flat metrics when comparisons are absent
        if (metrics != null && !metrics.isEmpty() && (t.getMetricComparisons() == null || t.getMetricComparisons().isEmpty())) {
            double cpu = doubleVal(metrics.get("container_cpu_usage_rate"));
            hits.add(evaluate("grafana_cpu_rate", BottleneckCategory.B_BACKEND_CPU, StatFormula.BASELINE_MULTIPLIER,
                    cpu, StatEvaluator.EvalContext.baselineMultiplier(
                            properties.getBaselines().getContainerCpuUsageRate(),
                            properties.getMultipliers().getCpuRate()),
                    1.0, stubMetrics,
                    "Pod CPU usage rate=%.2f (baseline %.2f)".formatted(cpu,
                            properties.getBaselines().getContainerCpuUsageRate())));

            double mem = doubleVal(metrics.get("container_memory_usage_rate"));
            hits.add(evaluate("grafana_memory_rate", BottleneckCategory.B_BACKEND_CPU, StatFormula.BASELINE_MULTIPLIER,
                    mem, StatEvaluator.EvalContext.baselineMultiplier(
                            properties.getBaselines().getContainerMemoryUsageRate(),
                            properties.getMultipliers().getMemoryRate()),
                    0.8, stubMetrics,
                    "Pod memory usage rate=%.2f (baseline %.2f)".formatted(mem,
                            properties.getBaselines().getContainerMemoryUsageRate())));

            double rejected = doubleVal(metrics.get("thread_rejected"));
            hits.add(evaluate("grafana_thread_rejected", BottleneckCategory.B_BACKEND_CPU, StatFormula.PRESENT,
                    rejected, StatEvaluator.EvalContext.present(), 1.2, stubMetrics,
                    "Thread pool rejected count=%.0f on pod %s".formatted(rejected, t.getPodName())));

            double queue = doubleVal(metrics.get("thread_queue"));
            hits.add(evaluate("grafana_thread_queue", BottleneckCategory.B_BACKEND_CPU, StatFormula.BASELINE_MULTIPLIER,
                    queue, StatEvaluator.EvalContext.baselineMultiplier(
                            properties.getBaselines().getThreadQueue(),
                            properties.getMultipliers().getThreadQueue()),
                    0.7, stubMetrics,
                    "Thread queue depth=%.0f (baseline %.0f)".formatted(queue,
                            properties.getBaselines().getThreadQueue())));

            double gcOld = doubleVal(metrics.get("gc_old_gen_ms"));
            hits.add(evaluate("grafana_gc_old_gen", BottleneckCategory.B_BACKEND_CPU, StatFormula.BASELINE_MULTIPLIER,
                    gcOld, StatEvaluator.EvalContext.baselineMultiplier(
                            properties.getBaselines().getGcOldGenMs(),
                            properties.getMultipliers().getGcOldGen()),
                    0.9, stubMetrics,
                    "Old-gen GC time=%.0fms (baseline %dms)".formatted(gcOld,
                            properties.getBaselines().getGcOldGenMs())));

            double esRejected = doubleVal(metrics.get("es_threadpool_search_rejected"));
            if (esRejected > 0 || metrics.containsKey("es_query_latency_ms")) {
                hits.add(evaluate("grafana_es_thread_rejected", BottleneckCategory.B_BACKEND_CPU,
                        StatFormula.PRESENT, esRejected, StatEvaluator.EvalContext.present(),
                        1.0, stubMetrics,
                        "ES threadpool search rejected=%.0f on host %s".formatted(
                                esRejected, metrics.getOrDefault("es_host", "es"))));

                double esLatency = doubleVal(metrics.get("es_query_latency_ms"));
                hits.add(evaluate("grafana_es_query_latency", BottleneckCategory.C_DATABASE,
                        StatFormula.THRESHOLD, esLatency,
                        StatEvaluator.EvalContext.threshold(properties.getThresholds().getKibanaEsTimeMs()),
                        0.9, stubMetrics,
                        "ES query latency=%.0fms on host %s".formatted(
                                esLatency, metrics.getOrDefault("es_host", "es"))));
            }
        }

        List<Double> waitTimes = kibanaLongs(t, "totalWaitTimeMs");
        if (!waitTimes.isEmpty()) {
            double maxWait = waitTimes.stream().mapToDouble(d -> d).max().orElse(0);
            hits.add(evaluate("kibana_total_wait", BottleneckCategory.B_BACKEND_CPU, StatFormula.THRESHOLD,
                    maxWait, StatEvaluator.EvalContext.threshold(properties.getThresholds().getKibanaWaitTimeMs()),
                    0.85, false,
                    "Kibana totalWaitTime max=%.0fms".formatted(maxWait)));
        }

        List<Double> execTimes = kibanaLongs(t, "totalExecTimeMs");
        if (!waitTimes.isEmpty() && !execTimes.isEmpty()) {
            double wait = waitTimes.stream().mapToDouble(d -> d).max().orElse(0);
            double exec = execTimes.stream().mapToDouble(d -> d).max().orElse(1);
            hits.add(evaluate("kibana_wait_exec_share", BottleneckCategory.B_BACKEND_CPU, StatFormula.SHARE_OF_TOTAL,
                    wait, StatEvaluator.EvalContext.share(exec + wait, 0.45),
                    0.75, false,
                    "Kibana wait=%.0fms vs exec+wait=%.0fms (queue pressure)".formatted(wait, exec + wait)));
        }
        return hits;
    }

    private List<HeuristicRuleHit> grafanaComparisonRules(Telemetry t) {
        List<HeuristicRuleHit> hits = new ArrayList<>();
        if (t.getMetricComparisons() == null || t.getMetricComparisons().isEmpty()) {
            return hits;
        }
        boolean stubMetrics = isStub(t.getPodMetrics());
        for (MetricComparison comparison : t.getMetricComparisons()) {
            if (!comparison.isTriggered()) {
                continue;
            }
            BottleneckCategory category = grafanaMetricEvaluator.categoryFor(comparison.getMetric());
            double weight = grafanaMetricEvaluator.weightFor(comparison.getMetric());
            double contribution = stubMetrics ? 0 : weight * Math.min(1.0, comparison.getStrength());
            hits.add(HeuristicRuleHit.builder()
                    .ruleId("grafana_cmp_" + comparison.getMetric())
                    .category(category)
                    .formula(comparison.getFormula())
                    .triggered(true)
                    .stubSource(stubMetrics)
                    .scoreContribution(contribution)
                    .evidence(comparison.getInterpretation() != null
                            ? comparison.getInterpretation()
                            : "Grafana %s verdict=%s ratio=%.2f".formatted(
                                    comparison.getMetric(), comparison.getVerdict(), comparison.getRatio()))
                    .build());
        }
        return hits;
    }

    private HeuristicRuleHit trackHarIssue(List<String> harIssues, String issueId, HeuristicRuleHit hit) {
        if (hit.isTriggered() && !harIssues.contains(issueId)) {
            harIssues.add(issueId);
        }
        return hit;
    }

    private long maxKibana(Telemetry t, String field) {
        return kibanaLongs(t, field).stream().mapToLong(Double::longValue).max().orElse(0);
    }

    private List<HarTimingView> harTimingViews(Telemetry t) {
        if (t.getSlowHarEntries() != null && !t.getSlowHarEntries().isEmpty()) {
            return t.getSlowHarEntries().stream().map(HarTimingView::from).toList();
        }
        return List.of(HarTimingView.fromPrimary(t));
    }

    private record HarTimingView(
            String label,
            long durationMs,
            long waitMs,
            long blockedMs,
            long receiveMs,
            long sendMs,
            long dnsMs,
            long connectMs,
            long sslMs,
            int responseStatus) {

        String suffix() {
            return label.isBlank() ? "" : "_" + label.replaceAll("[^a-zA-Z0-9]", "_");
        }

        static HarTimingView from(SlowHarEntry entry) {
            String label = entry.getApiKind().isBlank()
                    ? entry.getApiName()
                    : entry.getApiKind() + "/" + entry.getApiName();
            return new HarTimingView(label, entry.getDurationMs(), entry.getWaitMs(),
                    entry.getBlockedMs(), entry.getReceiveMs(), entry.getSendMs(),
                    entry.getDnsMs(), entry.getConnectMs(), entry.getSslMs(),
                    entry.getResponseStatus());
        }

        static HarTimingView fromPrimary(Telemetry t) {
            String label = t.getHarApiKind() != null && t.getHarApiName() != null
                    ? t.getHarApiKind() + "/" + t.getHarApiName() : "primary";
            return new HarTimingView(label,
                    nz(t.getHarDurationMs()), nz(t.getHarWaitMs()), nz(t.getHarBlockedMs()),
                    nz(t.getHarReceiveMs()), nz(t.getHarSendMs()), nz(t.getHarDnsMs()),
                    nz(t.getHarConnectMs()), nz(t.getHarSslMs()),
                    t.getHarResponseStatus() != null ? t.getHarResponseStatus() : 0);
        }

        private static long nz(Long v) {
            return v != null ? v : 0;
        }
    }

    // ── (C) Database / ES ───────────────────────────────────────────────────

    private List<HeuristicRuleHit> databaseRules(Telemetry t) {
        List<HeuristicRuleHit> hits = new ArrayList<>();

        if (t.getGraylogLogs() != null) {
            for (Map<String, Object> log : t.getGraylogLogs()) {
                boolean stub = isStub(log);
                String logKind = String.valueOf(log.getOrDefault("logKind", ""));

                if ("perfstats".equals(logKind)) {
                    long taken = longVal(log.get("timeTakenMs"));
                    hits.add(evaluate("graylog_perfstats_" + log.get("statName"), BottleneckCategory.C_DATABASE,
                            StatFormula.THRESHOLD, taken,
                            StatEvaluator.EvalContext.threshold(properties.getThresholds().getKibanaEsTimeMs()),
                            0.6, stub,
                            "Graylog perfstats [%s] timeTaken=%dms".formatted(log.get("statName"), taken)));
                    continue;
                }

                if (!"calltracking".equals(logKind) && longVal(log.get("esTimeTaken")) == 0) {
                    continue;
                }

                long db = longVal(log.get("dbTimeTaken"));
                long es = longVal(log.get("esTimeTaken"));
                long mongo = longVal(log.get("mongoTimeTaken"));
                long store = db + es + mongo;
                if (store <= 0) {
                    continue;
                }
                long apiTotal = longVal(log.get("apiTotalMs"));
                if (apiTotal <= 0) {
                    apiTotal = store + 200;
                }
                String stream = String.valueOf(log.get("stream"));
                hits.add(evaluate("graylog_store_share_" + stream, BottleneckCategory.C_DATABASE,
                        StatFormula.SHARE_OF_TOTAL, store,
                        StatEvaluator.EvalContext.share(apiTotal, properties.getThresholds().getDbShareOfApi()),
                        1.0, stub,
                        "Graylog [%s] store time db=%d es=%d mongo=%d is %.0f%% of apiTotal %dms".formatted(
                                stream, db, es, mongo, pct(store, apiTotal), apiTotal)));
            }
        }

        List<Double> esTimes = kibanaLongs(t, "esTimeMs");
        if (!esTimes.isEmpty()) {
            double maxEs = esTimes.stream().mapToDouble(d -> d).max().orElse(0);
            hits.add(evaluate("kibana_es_time", BottleneckCategory.C_DATABASE, StatFormula.THRESHOLD,
                    maxEs, StatEvaluator.EvalContext.threshold(properties.getThresholds().getKibanaEsTimeMs()),
                    1.0, false,
                    "Kibana esTotalTimeTakenInMillis max=%.0fms".formatted(maxEs)));

            if (esTimes.size() >= 2) {
                double latest = esTimes.get(0);
                hits.add(evaluate("kibana_es_median_deviation", BottleneckCategory.C_DATABASE,
                        StatFormula.MEDIAN_DEVIATION, latest,
                        StatEvaluator.EvalContext.medianDeviation(esTimes,
                                properties.getThresholds().getLogTimingDeviation()),
                        0.8, false,
                        "Kibana ES time %.0fms deviates from median %.0fms".formatted(
                                latest, StatEvaluator.median(esTimes))));
            }
        }

        long timedOutCount = countKibanaFlag(t, "timedOut", "true");
        hits.add(evaluate("kibana_es_timeout", BottleneckCategory.C_DATABASE, StatFormula.PRESENT,
                timedOutCount, StatEvaluator.EvalContext.present(), 1.1, false,
                "Kibana ES timedOut=true on %d log entries".formatted(timedOutCount)));

        return hits;
    }

    // ── (D) Exceptions ──────────────────────────────────────────────────────

    private List<HeuristicRuleHit> exceptionRules(Telemetry t) {
        List<HeuristicRuleHit> hits = new ArrayList<>();
        long errorCount = 0;
        if (t.getKibanaLogs() != null) {
            for (Map<String, Object> log : t.getKibanaLogs()) {
                boolean error = "ERROR".equalsIgnoreCase(String.valueOf(log.get("level")));
                boolean hasException = log.get("exceptionType") != null
                        && !String.valueOf(log.get("exceptionType")).isBlank();
                if (error || hasException) {
                    errorCount++;
                }
            }
        }
        hits.add(evaluate("kibana_error_logs", BottleneckCategory.D_EXCEPTION, StatFormula.PRESENT,
                errorCount, StatEvaluator.EvalContext.present(), 1.5, false,
                "Kibana error/exception log entries=%d".formatted(errorCount)));
        return hits;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private HeuristicRuleHit evaluate(String ruleId, BottleneckCategory category, StatFormula formula,
                                      double value, StatEvaluator.EvalContext ctx,
                                      double weight, boolean stubSource, String evidenceTemplate) {
        boolean triggered = StatEvaluator.triggered(formula, value, ctx);
        double contribution = stubSource ? 0 : StatEvaluator.scoreContribution(formula, value, ctx, weight);
        return HeuristicRuleHit.builder()
                .ruleId(ruleId)
                .category(category)
                .formula(formula.name())
                .triggered(triggered)
                .stubSource(stubSource)
                .scoreContribution(contribution)
                .evidence(triggered ? evidenceTemplate : evidenceTemplate + " [not triggered]")
                .build();
    }

    private BottleneckCategory pickPrimary(Map<BottleneckCategory, Double> scores) {
        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= properties.getMinConfidenceScore())
                .map(Map.Entry::getKey)
                .orElse(BottleneckCategory.UNKNOWN);
    }

    private FaultType mapFault(BottleneckCategory category) {
        return switch (category) {
            case A_NETWORK -> FaultType.NETWORK_TIMEOUT;
            case B_BACKEND_CPU -> FaultType.CPU_SATURATION;
            case C_DATABASE -> FaultType.DATABASE_BOTTLENECK;
            case D_EXCEPTION -> FaultType.UNHANDLED_EXCEPTION;
            case UNKNOWN -> FaultType.UNKNOWN;
        };
    }

    private List<String> defaultActions(RcaHeuristicsResult heuristics, StructuredFindings findings) {
        String pod = findings != null && findings.getPodName() != null ? findings.getPodName() : "the serving pod";
        return switch (heuristics.getPrimaryCategory()) {
            case A_NETWORK -> List.of(
                    "Compare HAR wait vs receive; check CDN/proxy and TLS path",
                    "Run traceroute/MTR from client region to origin");
            case B_BACKEND_CPU -> List.of(
                    "Inspect pod CPU/memory and thread pool on " + pod,
                    "Check GC logs and queue depth at incident time");
            case C_DATABASE -> List.of(
                    "Review Graylog db/es/mongo timings for the requestId",
                    "Check ES query plan, index count, and timeout flags in Kibana");
            case D_EXCEPTION -> List.of(
                    "Inspect Kibana ERROR logs for stack traces tied to requestId",
                    "Fix or handle the thrown exception on the critical path");
            case UNKNOWN -> List.of("Collect more overlapping logs/metrics for the time window");
        };
    }

    private List<Double> kibanaLongs(Telemetry t, String field) {
        if (t.getKibanaLogs() == null) {
            return List.of();
        }
        return t.getKibanaLogs().stream()
                .map(log -> log.get(field))
                .filter(Objects::nonNull)
                .map(v -> (double) longVal(v))
                .filter(v -> v > 0)
                .toList();
    }

    private long countKibanaFlag(Telemetry t, String field, String expected) {
        if (t.getKibanaLogs() == null) {
            return 0;
        }
        return t.getKibanaLogs().stream()
                .filter(log -> expected.equalsIgnoreCase(String.valueOf(log.get(field))))
                .count();
    }

    private static boolean isStub(Map<String, Object> map) {
        return map != null && "stub".equals(map.get("source"));
    }

    private static long nz(Long v) {
        return v != null ? v : 0;
    }

    private static long longVal(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleVal(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double pct(long part, long total) {
        return total > 0 ? (100.0 * part / total) : 0;
    }
}
