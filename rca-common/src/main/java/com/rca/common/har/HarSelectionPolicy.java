package com.rca.common.har;

/**
 * Heuristic limits for which HAR entries to include in RCA.
 */
public final class HarSelectionPolicy {

    /** Default minimum duration (ms) when caller passes slowThresholdMs=0. */
    public static final long DEFAULT_MIN_SLOW_MS = 200L;
    /** Hard cap on APIs analyzed per HAR (slowest among p50+ band). */
    public static final int MAX_SLOW_ENTRIES = 10;
    /** Percentile cutoffs for tier assignment (session duration distribution). */
    public static final int PERCENTILE_P50 = 50;
    public static final int PERCENTILE_P75 = 75;
    public static final int PERCENTILE_P95 = 95;
    /** Tukey fence multiplier: Q3 + multiplier × IQR (informational / legacy summary). */
    public static final double IQR_MULTIPLIER = 1.5;
    /** Payload size (bytes) for side-large tier and forensics. */
    public static final long LARGE_PAYLOAD_BYTES = 500_000L;
    /** Receive time must be at least this share of total for side-large. */
    public static final double SIDE_LARGE_RECEIVE_SHARE = 0.35;
    /** Wait must stay below this share for side-large (download-dominated). */
    public static final double SIDE_LARGE_MAX_WAIT_SHARE = 0.50;

    private HarSelectionPolicy() {
    }

    public static long effectiveMinSlowMs(long slowThresholdMs) {
        if (slowThresholdMs <= 0) {
            return DEFAULT_MIN_SLOW_MS;
        }
        return slowThresholdMs;
    }

    public static boolean isEnterpriseHeavyEndpoint(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return false;
        }
        return switch (apiName) {
            case "caseStreamFeed", "universalCases", "paginatedAssociatedMessagesForCase" -> true;
            default -> false;
        };
    }
}
