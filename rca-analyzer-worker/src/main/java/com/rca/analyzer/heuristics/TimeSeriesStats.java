package com.rca.analyzer.heuristics;

import com.rca.common.model.MetricStatSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TimeSeriesStats {

    private TimeSeriesStats() {
    }

    static MetricStatSummary summarize(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return MetricStatSummary.builder().sampleCount(0).build();
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double sum = sorted.stream().mapToDouble(d -> d).sum();
        return MetricStatSummary.builder()
                .mean(sum / n)
                .median(median(sorted))
                .p95(percentile(sorted, 0.95))
                .max(sorted.get(n - 1))
                .last(sorted.get(n - 1))
                .sum(sum)
                .sampleCount(n)
                .build();
    }

    static double pick(MetricStatSummary summary, String aggregation) {
        if (summary == null || summary.getSampleCount() <= 0) {
            return 0;
        }
        return switch (aggregation == null ? "last" : aggregation.toLowerCase()) {
            case "mean" -> summary.getMean();
            case "median" -> summary.getMedian();
            case "p95" -> summary.getP95();
            case "max" -> summary.getMax();
            case "sum" -> summary.getSum();
            default -> summary.getLast();
        };
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = p * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double weight = rank - low;
        return sorted.get(low) * (1 - weight) + sorted.get(high) * weight;
    }
}
