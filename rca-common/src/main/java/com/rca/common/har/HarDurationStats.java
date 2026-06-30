package com.rca.common.har;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Session-level API duration percentiles for tier assignment. */
public final class HarDurationStats {

    private final long p50Ms;
    private final long p75Ms;
    private final long p95Ms;

    public HarDurationStats(long p50Ms, long p75Ms, long p95Ms) {
        this.p50Ms = p50Ms;
        this.p75Ms = p75Ms;
        this.p95Ms = p95Ms;
    }

    public long getP50Ms() {
        return p50Ms;
    }

    public long getP75Ms() {
        return p75Ms;
    }

    public long getP95Ms() {
        return p95Ms;
    }

    public static HarDurationStats fromDurations(List<Long> durationsMs) {
        if (durationsMs == null || durationsMs.isEmpty()) {
            return new HarDurationStats(0, 0, 0);
        }
        List<Long> sorted = new ArrayList<>(durationsMs);
        Collections.sort(sorted);
        return new HarDurationStats(
                percentile(sorted, HarSelectionPolicy.PERCENTILE_P50),
                percentile(sorted, HarSelectionPolicy.PERCENTILE_P75),
                percentile(sorted, HarSelectionPolicy.PERCENTILE_P95));
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
