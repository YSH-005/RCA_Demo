package com.rca.common.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimelineSegment {
    private String phase;
    private long durationMs;
    private String source;
    /** Optional link to bottleneck category, e.g. A_NETWORK */
    private String categoryHint;
}
