package com.rca.common.har;

/**
 * How an API ranks for slowness RCA within a HAR session.
 */
public enum HarApiTier {
    /** Priority Sprinklr endpoint + session outlier (or top latency). */
    CRITICAL,
    /** Statistical outlier or clearly above session IQR fence. */
    HIGH,
    /** Large required payload; download leg dominates (informational for slowness split). */
    SIDE_LARGE
}
