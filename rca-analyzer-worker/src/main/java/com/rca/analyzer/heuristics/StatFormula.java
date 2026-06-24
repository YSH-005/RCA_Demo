package com.rca.analyzer.heuristics;

/**
 * How a metric is evaluated — different signal types need different math.
 */
public enum StatFormula {
    /** value &gt;= thresholdMs (or absolute threshold for counts/rates). */
    THRESHOLD,
    /** numerator / denominator &gt;= ratioThreshold (e.g. DB share of API time). */
    SHARE_OF_TOTAL,
    /** value &gt;= baseline * multiplier (rates, queue depth vs normal). */
    BASELINE_MULTIPLIER,
    /** value - baseline &gt;= delta (absolute excess over baseline). */
    DELTA_OVER_BASELINE,
    /** count &gt; 0 or boolean flag present. */
    PRESENT,
    /**
     * |value - median| / max(median, epsilon) &gt;= deviationRatio.
     * Used when multiple samples exist (e.g. several log entries).
     */
    MEDIAN_DEVIATION,
    /**
     * incident / baseline &gt;= multiplier AND (incident - baseline) &gt;= minDelta.
     * Handles chronically high metrics that are normal for the pod.
     */
    RELATIVE_BASELINE,
    /**
     * incident &gt; 0 while baseline max is below threshold (event anomaly).
     */
    PRESENT_IF_BASELINE_LOW
}
