package com.rca.analyzer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "rca.heuristics")
public class HeuristicsProperties {

    /** Minimum total score to pick a category (else UNKNOWN). */
    private double minConfidenceScore = 0.25;

    private Thresholds thresholds = new Thresholds();
    private Baselines baselines = new Baselines();
    private Multipliers multipliers = new Multipliers();
    private Grafana grafana = new Grafana();
    private Map<String, GrafanaMetricRule> grafanaMetrics = new HashMap<>();

    @Data
    public static class Grafana {
        /** Hours before incident window start for baseline range. */
        private int baselineHours = 24;
        /** Minutes gap between baseline end and incident start. */
        private int baselineGapMinutes = 15;
    }

    @Data
    public static class GrafanaMetricRule {
        private String incidentAggregation = "p95";
        private String baselineAggregation = "median";
        private String formula = "RELATIVE_BASELINE";
        private double multiplier = 1.25;
        private double minDelta = 0;
        private String category = "B_BACKEND_CPU";
        private double weight = 0.8;
        private double floor = 0;
        private double baselineCeiling = 0.5;
    }

    @Data
    public static class Thresholds {
        /** HAR network leg (dns+connect+ssl) share of total duration. */
        private double harNetworkShare = 0.35;
        /** HAR DNS absolute threshold (ms). */
        private long harDnsMs = 200;
        /** HAR DNS share of total duration. */
        private double harDnsShare = 0.20;
        /** HAR TCP connect threshold (ms). */
        private long harConnectMs = 300;
        /** HAR TLS handshake threshold (ms). */
        private long harSslMs = 300;
        /** H09 — selected slow API absolute duration threshold (ms). */
        private long harSlowApiMs = 3000;
        /** HAR TTFB wait share of total duration. */
        private double harWaitShare = 0.50;
        private long harWaitMs = 2000;
        private double harBlockedShare = 0.15;
        private double harReceiveShare = 0.30;
        private long harSendMs = 500;
        private long harBackendGapMs = 1000;
        private long kibanaNetworkTimeMs = 500;
        /** DB+ES+mongo share of Graylog api total time. */
        private double dbShareOfApi = 0.70;
        private long kibanaEsTimeMs = 1000;
        private long kibanaWaitTimeMs = 2000;
        /** Median deviation ratio for multi-sample log timings. */
        private double logTimingDeviation = 0.50;
    }

    @Data
    public static class Baselines {
        private double containerCpuUsageRate = 0.55;
        private double containerMemoryUsageRate = 0.60;
        private double threadQueue = 5;
        private long gcOldGenMs = 200;
        private long gcYoungGenMs = 100;
        /** Mongo Review cpu_total fallback when baseline window is empty. */
        private double mongoCpuTotal = 0.75;
        /** Mongo Review iowait % fallback. */
        private double mongoCpuIowaitPct = 15.0;
        /** Mongo Review load_avg_one fallback. */
        private double mongoLoadAvg = 4.0;
    }

    @Data
    public static class Multipliers {
        private double cpuRate = 1.25;
        private double memoryRate = 1.20;
        private double threadQueue = 2.0;
        private double gcOldGen = 2.0;
    }

    /** Per-metric formula overrides keyed by metric name (optional extension point). */
    private Map<String, String> metricFormulas = defaultMetricFormulas();

    private static Map<String, String> defaultMetricFormulas() {
        Map<String, String> m = new HashMap<>();
        m.put("har_network_share", "SHARE_OF_TOTAL");
        m.put("har_wait_share", "SHARE_OF_TOTAL");
        m.put("container_cpu_usage_rate", "BASELINE_MULTIPLIER");
        m.put("container_memory_usage_rate", "BASELINE_MULTIPLIER");
        m.put("thread_rejected", "PRESENT");
        m.put("thread_queue", "BASELINE_MULTIPLIER");
        m.put("gc_old_gen_ms", "BASELINE_MULTIPLIER");
        m.put("db_store_share", "SHARE_OF_TOTAL");
        m.put("kibana_es_time", "THRESHOLD");
        m.put("error_log_count", "PRESENT");
        return m;
    }
}
