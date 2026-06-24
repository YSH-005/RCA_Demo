package com.rca.analyzer.heuristics;

import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DisambiguationEngine {

    private DisambiguationEngine() {
    }

    static List<String> analyze(Telemetry telemetry, RcaHeuristicsResult result) {
        List<String> notes = new ArrayList<>();
        long wait = nz(telemetry.getHarWaitMs());
        long duration = nz(telemetry.getHarDurationMs());
        long networkLeg = nz(telemetry.getHarDnsMs()) + nz(telemetry.getHarConnectMs()) + nz(telemetry.getHarSslMs());

        long kibanaWait = maxKibana(telemetry, "totalWaitTimeMs");
        long kibanaExec = maxKibana(telemetry, "totalExecTimeMs");
        long kibanaEs = maxKibana(telemetry, "esTimeMs");
        long kibanaNetwork = maxKibana(telemetry, "networkTimeMs");
        long explained = kibanaWait + kibanaExec + kibanaEs;
        long gap = wait - explained;

        if (wait > 1000 && gap > 1000) {
            notes.add("HAR wait=%dms but Kibana wait+exec+es=%dms — %.0fms unaccounted (proxy/ingress/network)".formatted(
                    wait, explained, (double) gap));
        } else if (wait > 500 && explained > 0 && gap < 200) {
            notes.add("HAR wait=%dms largely explained by Kibana backend timings (%dms)".formatted(wait, explained));
        }

        double upstreamShare = maxIngressUpstreamShare(telemetry);
        if (kibanaWait > 500 && kibanaExec > 0 && kibanaWait > kibanaExec * 10) {
            notes.add("Kibana queue wait=%dms dominates exec=%dms — backend thread/queue pressure (B)".formatted(
                    kibanaWait, kibanaExec));
        }

        if (upstreamShare >= 0.80) {
            notes.add("Ingress upstream is %.0f%% of request time — slowness is at origin, not client DNS/TLS".formatted(
                    upstreamShare * 100));
            if (result.getPrimaryCategory() == BottleneckCategory.A_NETWORK) {
                notes.add("High TTFB with high upstream share — consider B/C instead of pure network");
            }
        }

        if (networkLeg > 0 && duration > 0 && (double) networkLeg / duration < 0.15
                && wait > 2000 && kibanaEs >= 800) {
            notes.add("Low HAR network leg but high Kibana ES time — database/ES is likely root cause");
        }

        if (kibanaNetwork > 300 && result.getScores().getOrDefault(BottleneckCategory.A_NETWORK, 0.0) > 0.3) {
            notes.add("Kibana REQUEST_RECEIVED_NETWORK_TIME=%dms corroborates network category".formatted(kibanaNetwork));
        }

        long storeMs = maxGraylogStore(telemetry);
        if (storeMs > 0 && kibanaEs > 0 && Math.abs(storeMs - kibanaEs) < kibanaEs * 0.5) {
            notes.add("Graylog calltracking store time aligns with Kibana ES time — strong C signal");
        }

        return notes;
    }

    private static double maxIngressUpstreamShare(Telemetry telemetry) {
        if (telemetry.getGraylogLogs() == null) {
            return 0;
        }
        double max = 0;
        for (Map<String, Object> log : telemetry.getGraylogLogs()) {
            if (!"ingress".equals(log.get("logKind"))) {
                continue;
            }
            long request = longVal(log.get("requestTimeMs"));
            long upstream = longVal(log.get("upstreamResponseTimeMs"));
            if (request > 0) {
                max = Math.max(max, (double) upstream / request);
            }
        }
        return max;
    }

    private static long maxGraylogStore(Telemetry telemetry) {
        if (telemetry.getGraylogLogs() == null) {
            return 0;
        }
        long max = 0;
        for (Map<String, Object> log : telemetry.getGraylogLogs()) {
            if (!"calltracking".equals(log.get("logKind"))) {
                continue;
            }
            max = Math.max(max, longVal(log.get("esTimeTaken")) + longVal(log.get("mongoTimeTaken"))
                    + longVal(log.get("dbTimeTaken")));
        }
        return max;
    }

    private static long maxKibana(Telemetry telemetry, String field) {
        if (telemetry.getKibanaLogs() == null) {
            return 0;
        }
        return telemetry.getKibanaLogs().stream()
                .mapToLong(l -> longVal(l.get(field)))
                .max()
                .orElse(0);
    }

    private static long nz(Long v) {
        return v != null ? v : 0;
    }

    private static long longVal(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
