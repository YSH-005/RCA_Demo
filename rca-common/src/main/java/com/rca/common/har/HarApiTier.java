package com.rca.common.har;

/**
 * Session percentile band for API slowness within a HAR capture.
 * Thresholds come from {@link HarDurationStats} (p50 / p75 / p95 on duration).
 */
public enum HarApiTier {
    /** Duration at or above session p95 — highest priority for log/metric correlation. */
    HIGHLY_CRITICAL,
    /** Duration at or above session p75 (below p95). */
    CRITICAL,
    /** Duration at or above session p50 (below p75) — included in slow set up to cap. */
    HIGH,
    /** Below session p50 — baseline; excluded from slow-entry log analysis. */
    NORMAL
}
