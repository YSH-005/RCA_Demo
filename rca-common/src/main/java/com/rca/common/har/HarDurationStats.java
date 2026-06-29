package com.rca.common.har;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session-level API duration distribution for IQR-based outlier detection.
 */
public final class HarDurationStats {

    private final int count;
    private final long medianMs;
    private final long q1Ms;
    private final long q3Ms;
    private final long p95Ms;
    private final long iqrMs;
    private final long outlierThresholdMs;

    public HarDurationStats(int count, long medianMs, long q1Ms, long q3Ms, long p95Ms, long iqrMs, long outlierThresholdMs) {
        this.count = count;
        this.medianMs = medianMs;
        this.q1Ms = q1Ms;
        this.q3Ms = q3Ms;
        this.p95Ms = p95Ms;
        this.iqrMs = iqrMs;
        this.outlierThresholdMs = outlierThresholdMs;
    }

    public int getCount() {
        return count;
    }

    public long getMedianMs() {
        return medianMs;
    }

    public long getQ1Ms() {
        return q1Ms;
    }

    public long getQ3Ms() {
        return q3Ms;
    }

    /** Session p75 — same value as {@link #getQ3Ms()}. */
    public long getP75Ms() {
        return q3Ms;
    }

    /** Session p50 — same value as {@link #getMedianMs()}. */
    public long getP50Ms() {
        return medianMs;
    }

    public long getP95Ms() {
        return p95Ms;
    }

    public long getIqrMs() {
        return iqrMs;
    }

    public long getOutlierThresholdMs() {
        return outlierThresholdMs;
    }

    public static HarDurationStats fromDurations(List<Long> durationsMs, long floorMs) {
        if (durationsMs == null || durationsMs.isEmpty()) {
            return new HarDurationStats(0, 0, 0, 0, 0, 0, floorMs);
        }
        List<Long> sorted = new ArrayList<>(durationsMs);
        Collections.sort(sorted);

        long q1 = percentile(sorted, 25);
        long median = percentile(sorted, 50);
        long q3 = percentile(sorted, 75);
        long p95 = percentile(sorted, 95);
        long iqr = Math.max(0, q3 - q1);

        long tukeyFence = q3 + Math.round(iqr * HarSelectionPolicy.IQR_MULTIPLIER);
        long outlierThreshold;
        if (sorted.size() < 4) {
            outlierThreshold = Math.max(floorMs, Math.max(q3, median * 2));
        } else if (iqr == 0) {
            outlierThreshold = Math.max(floorMs, q3);
        } else {
            outlierThreshold = Math.max(floorMs, tukeyFence);
        }

        return new HarDurationStats(sorted.size(), median, q1, q3, p95, iqr, outlierThreshold);
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double weight = rank - low;
        return Math.round(sorted.get(low) * (1 - weight) + sorted.get(high) * weight);
    }
}
