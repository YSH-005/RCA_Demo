package com.rca.analyzer.heuristics;

import com.rca.common.enums.BottleneckCategory;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.Telemetry;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfidenceCalculator {

    record SourceCounts(int available, int agreeing) {}

    private ConfidenceCalculator() {
    }

    static double compute(
            Map<BottleneckCategory, Double> scores,
            BottleneckCategory primary,
            int sourceAgreement,
            int sourcesAvailable,
            double minConfidenceScore) {

        if (primary == BottleneckCategory.UNKNOWN) {
            return 0.2;
        }
        double top = scores.getOrDefault(primary, 0.0);
        double second = scores.entrySet().stream()
                .filter(e -> e.getKey() != primary)
                .mapToDouble(Map.Entry::getValue)
                .max()
                .orElse(0);
        double margin = top - second;
        double normalizedTop = Math.min(1.0, top / Math.max(minConfidenceScore * 3, 0.75));
        double agreement = sourcesAvailable > 0 ? (double) sourceAgreement / sourcesAvailable : 0;
        double dataQuality = sourcesAvailable > 0 ? (double) sourcesAvailable / 4.0 : 0.25;

        return Math.min(0.95,
                0.25
                        + 0.35 * normalizedTop
                        + 0.20 * Math.min(1.0, margin / Math.max(top, 0.01))
                        + 0.15 * agreement
                        + 0.05 * dataQuality);
    }

    static Map<String, Object> breakdown(
            RcaHeuristicsResult result,
            BottleneckCategory primary,
            int sourceAgreement,
            int sourcesAvailable) {

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryCategory", primary.name());
        map.put("primaryScore", result.getScores().getOrDefault(primary, 0.0));
        map.put("sourceAgreement", sourceAgreement + "/" + sourcesAvailable);
        map.put("scores", new EnumMap<>(result.getScores()));
        return map;
    }

    static SourceCounts countSources(Telemetry telemetry) {
        int available = 0;
        int agreeing = 0;

        boolean har = telemetry.getHarDurationMs() != null && telemetry.getHarDurationMs() > 0;
        boolean kibana = telemetry.getKibanaLogs() != null && !telemetry.getKibanaLogs().isEmpty();
        boolean graylog = telemetry.getGraylogLogs() != null && !telemetry.getGraylogLogs().isEmpty()
                && telemetry.getGraylogLogs().stream().noneMatch(l -> "stub".equals(l.get("source")));
        boolean grafana = (telemetry.getMetricComparisons() != null && !telemetry.getMetricComparisons().isEmpty())
                || (telemetry.getPodMetrics() != null && !telemetry.getPodMetrics().isEmpty()
                && !"stub".equals(telemetry.getPodMetrics().get("source")));

        if (har) {
            available++;
            agreeing++;
        }
        if (kibana) {
            available++;
            agreeing++;
        }
        if (graylog) {
            available++;
            agreeing++;
        }
        if (grafana) {
            available++;
            agreeing++;
        }
        return new SourceCounts(available, agreeing);
    }
}
