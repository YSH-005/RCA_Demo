package com.rca.common.error;

import com.rca.common.enums.BottleneckCategory;
import lombok.Builder;
import lombok.Data;

/**
 * Result of reading a stack trace to infer what kind of failure occurred.
 */
@Data
@Builder
public class StackTraceClassification {
    /** Root exception class, e.g. com.spr.exceptions.ListeningQueryValidationException */
    private String exceptionType;
    /** Short message from the first line after the colon */
    private String exceptionMessage;
    /**
     * High-level kind derived from exception type + frames.
     * validation | auth | timeout | database | network | thread_pool | external | application | unknown
     */
    private String kind;
    /** Which bottleneck bucket this points to first */
    private BottleneckCategory inferredCategory;
    /** First com.spr.* frame (skips java.* / framework noise) */
    private String topApplicationFrame;
    /** Plain-language explanation for RCA / Gemini */
    private String reason;
    /** Whether Grafana infra metrics are relevant for this failure */
    private boolean infraMetricsRelevant;
    /** Top frames, one per line, truncated for evidence */
    private String stackTraceSummary;
}
