package com.rca.common.har;

/**
 * Heuristic limits for which HAR entries to include in RCA.
 */
public final class HarSelectionPolicy {

    /** Default minimum duration (ms) when caller passes slowThresholdMs=0. */
    public static final long DEFAULT_MIN_SLOW_MS = 2000L;
    /** Hard cap on slow APIs analyzed per HAR. */
    public static final int MAX_SLOW_ENTRIES = 6;
    /** All four Sprinklr priority endpoints can be included when slow. */
    public static final int MAX_PRIORITY_ENTRIES = 4;
    /**
     * Non-priority API included only if duration &gt;= primaryDuration × this share.
     */
    public static final double SECONDARY_SHARE_OF_PRIMARY = 0.35;

    private HarSelectionPolicy() {
    }

    public static long effectiveMinSlowMs(long slowThresholdMs) {
        if (slowThresholdMs <= 0) {
            return DEFAULT_MIN_SLOW_MS;
        }
        return slowThresholdMs;
    }
}
