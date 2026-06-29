package com.rca.analyzer.heuristics;

import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.HeuristicRuleHit;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.SlowHarEntry;
import com.rca.common.model.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RcaHeuristicsServiceTest {

    private final RcaHeuristicsService service = new RcaHeuristicsService(
            new HeuristicsProperties(), new GrafanaMetricEvaluator(new HeuristicsProperties()));

    @Test
    void stubGrafanaDoesNotDrivePrimaryCategory() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setHarWaitMs(100L);
        telemetry.setHarDurationMs(500L);
        telemetry.setPodMetrics(Map.of(
                "source", "stub",
                "container_cpu_usage_rate", 0.99,
                "thread_rejected", 50.0));

        RcaHeuristicsResult result = service.analyze(telemetry);

        assertFalse(result.getPrimaryCategory() == BottleneckCategory.B_BACKEND_CPU);
        assertTrue(result.getRuleHits().stream()
                .filter(h -> h.getRuleId().startsWith("grafana_"))
                .allMatch(HeuristicRuleHit::isStubSource));
    }

    @Test
    void kibanaErrorLogsTriggerExceptionCategory() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setKibanaLogs(List.of(kibanaLog("ERROR", 600L, 100L, 50L, 10L)));

        RcaHeuristicsResult result = service.analyze(telemetry);

        assertTrue(result.getRuleHits().stream()
                .anyMatch(h -> "kibana_error_logs".equals(h.getRuleId()) && h.isTriggered()));
        assertEquals(BottleneckCategory.D_EXCEPTION, result.getPrimaryCategory());
    }

    @Test
    void kibanaAndHarNetworkSignalsScoreNetworkCategory() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setHarWaitMs(6000L);
        telemetry.setHarDurationMs(6100L);
        telemetry.setKibanaLogs(List.of(kibanaLog("INFO", 600L, 100L, 50L, 10L)));

        RcaHeuristicsResult result = service.analyze(telemetry);

        assertEquals(BottleneckCategory.A_NETWORK, result.getPrimaryCategory());
        assertTrue(result.getScores().get(BottleneckCategory.A_NETWORK) > 0);
    }

    private Telemetry baseTelemetry() {
        Telemetry telemetry = new Telemetry();
        telemetry.setRequestId("space-test-request-id");
        telemetry.setHarDnsMs(0L);
        telemetry.setHarConnectMs(0L);
        telemetry.setHarSslMs(0L);
        telemetry.setHarSendMs(0L);
        telemetry.setHarReceiveMs(0L);
        telemetry.setGraylogLogs(List.of(stubGraylogEntry()));
        return telemetry;
    }

    @Test
    void validationExceptionFromStackTraceTriggersExceptionCategory() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setErrorContext(com.rca.common.error.ErrorContextMerger.enrich(
                com.rca.common.model.ErrorContext.builder()
                .exceptionStackTrace("""
                        com.spr.exceptions.ListeningQueryValidationException: filter missing
                        \tat com.spr.listening.service.validation.AbstractListeningRequestValidationService.validateMandatoryFilterPresent(AbstractListeningRequestValidationService.java:72)
                        """)
                .exceptionType("com.spr.exceptions.ListeningQueryValidationException")
                .supportReference("202606260553-19cf95d9-0db2-46a5-b479-67d5e337e0d4")
                .source("merged")
                .build()));

        RcaHeuristicsResult result = service.analyze(telemetry);

        assertEquals(BottleneckCategory.D_EXCEPTION, result.getPrimaryCategory());
        assertTrue(result.getRuleHits().stream()
                .anyMatch(h -> "kibana_exception_type".equals(h.getRuleId()) && h.isTriggered()));
    }

    private Map<String, Object> kibanaLog(String level, long networkMs, long waitMs, long execMs, long esMs) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("source", "kibana");
        log.put("level", level);
        log.put("networkTimeMs", networkMs);
        log.put("totalWaitTimeMs", waitMs);
        log.put("totalExecTimeMs", execMs);
        log.put("esTimeMs", esMs);
        log.put("podName", "webui-app-deployment-test-pod");
        return log;
    }

    @Test
    void syntheticGraylogCalltrackingAndPerfstatsScoreDatabase() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setGraylogLogs(List.of(
                syntheticCalltracking(2100, 400, 200, 2800),
                syntheticPerfstat("ESQueryExecution", 2100)));
        telemetry.setHarWaitMs(100L);
        telemetry.setHarDurationMs(500L);

        RcaHeuristicsResult result = service.analyze(telemetry);

        assertTrue(result.getRuleHits().stream()
                .anyMatch(h -> h.getRuleId().startsWith("graylog_store_share_") && h.isTriggered()
                        && !h.isStubSource()));
        assertTrue(result.getRuleHits().stream()
                .anyMatch(h -> h.getRuleId().startsWith("graylog_perfstats_") && h.isTriggered()
                        && !h.isStubSource()));
    }

    private Map<String, Object> syntheticCalltracking(long es, long mongo, long db, long apiTotal) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("source", "graylog");
        log.put("logKind", "calltracking");
        log.put("stream", "calltrackingbydb");
        log.put("dbTimeTaken", db);
        log.put("esTimeTaken", es);
        log.put("mongoTimeTaken", mongo);
        log.put("apiTotalMs", apiTotal);
        return log;
    }

    private Map<String, Object> syntheticPerfstat(String statName, long timeTakenMs) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("source", "graylog");
        log.put("logKind", "perfstats");
        log.put("stream", "perfstats");
        log.put("statName", statName);
        log.put("timeTakenMs", timeTakenMs);
        return log;
    }

    @Test
    void multiApiLogsScaleScoreByAnalysisWeight() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setSlowHarEntries(List.of(
                SlowHarEntry.builder().requestId("req-heavy").waitMs(9000).build(),
                SlowHarEntry.builder().requestId("req-light").waitMs(1000).build()));

        Map<String, Object> heavyLog = kibanaLog("INFO", 600L, 500L, 50L, 2100L);
        heavyLog.put("analysisWeight", 0.9);
        heavyLog.put("slowApiLabel", "graphql/heavyQuery");

        Map<String, Object> lightLog = kibanaLog("INFO", 600L, 500L, 50L, 2100L);
        lightLog.put("analysisWeight", 0.1);
        lightLog.put("slowApiLabel", "rest/lightCall");

        telemetry.setKibanaLogs(List.of(heavyLog, lightLog));
        telemetry.setGraylogLogs(List.of(
                weightedGraylogCalltracking(heavyLog, 2100, 400, 200, 2800),
                weightedGraylogCalltracking(lightLog, 2100, 400, 200, 2800)));

        RcaHeuristicsResult result = service.analyze(telemetry);

        double heavyEs = result.getRuleHits().stream()
                .filter(h -> h.getRuleId().contains("kibana_es_time_graphql_heavyQuery"))
                .mapToDouble(HeuristicRuleHit::getScoreContribution)
                .findFirst()
                .orElse(0);
        double lightEs = result.getRuleHits().stream()
                .filter(h -> h.getRuleId().contains("kibana_es_time_rest_lightCall"))
                .mapToDouble(HeuristicRuleHit::getScoreContribution)
                .findFirst()
                .orElse(0);

        assertTrue(heavyEs > lightEs);
        assertTrue(heavyEs / lightEs > 5);
    }

    @Test
    void grafanaComparisonsScaleByAnalysisWeight() {
        Telemetry telemetry = baseTelemetry();
        telemetry.setSlowHarEntries(List.of(
                SlowHarEntry.builder().requestId("req-a").waitMs(7500).build(),
                SlowHarEntry.builder().requestId("req-b").waitMs(2500).build()));
        telemetry.setPodMetrics(Map.of("source", "grafana", "podName", "pod-1"));
        telemetry.setMetricComparisons(List.of(
                MetricComparison.builder()
                        .metric("container_cpu_usage_rate")
                        .formula("BASELINE_MULTIPLIER")
                        .triggered(true)
                        .strength(1.0)
                        .analysisWeight(0.75)
                        .requestId("req-a")
                        .interpretation("CPU spike req-a")
                        .build(),
                MetricComparison.builder()
                        .metric("container_cpu_usage_rate")
                        .formula("BASELINE_MULTIPLIER")
                        .triggered(true)
                        .strength(1.0)
                        .analysisWeight(0.25)
                        .requestId("req-b")
                        .interpretation("CPU spike req-b")
                        .build()));

        RcaHeuristicsResult result = service.analyze(telemetry);

        double heavyCpu = result.getRuleHits().stream()
                .filter(h -> h.getRuleId().contains("req_a"))
                .mapToDouble(HeuristicRuleHit::getScoreContribution)
                .findFirst()
                .orElse(0);
        double lightCpu = result.getRuleHits().stream()
                .filter(h -> h.getRuleId().contains("req_b"))
                .mapToDouble(HeuristicRuleHit::getScoreContribution)
                .findFirst()
                .orElse(0);

        assertTrue(heavyCpu > lightCpu);
        assertTrue(Math.abs(heavyCpu / lightCpu - 3.0) < 0.2);
    }

    private Map<String, Object> weightedGraylogCalltracking(
            Map<String, Object> sourceLog, long es, long mongo, long db, long apiTotal) {
        Map<String, Object> log = syntheticCalltracking(es, mongo, db, apiTotal);
        log.put("analysisWeight", sourceLog.get("analysisWeight"));
        log.put("slowApiLabel", sourceLog.get("slowApiLabel"));
        return log;
    }

    private Map<String, Object> stubGraylogEntry() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("source", "stub");
        log.put("stream", "calltrackingbydb");
        log.put("dbTimeTaken", 3000L);
        log.put("esTimeTaken", 0L);
        log.put("mongoTimeTaken", 0L);
        log.put("apiTotalMs", 3200L);
        return log;
    }
}
