package com.rca.analyzer.heuristics;

import com.rca.analyzer.config.HeuristicsProperties;
import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.HeuristicRuleHit;
import com.rca.common.model.RcaHeuristicsResult;
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
