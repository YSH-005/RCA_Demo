package com.rca.common.model;

import com.rca.common.enums.BottleneckCategory;
import lombok.Builder;
import lombok.Data;

/**
 * Merged error signals from HAR response bodies and Kibana error logs.
 */
@Data
@Builder
public class ErrorContext {
    private String supportReference;
    private String errorMessage;
    private String exceptionStackTrace;
    private String exceptionType;
    /** har | kibana | merged | none */
    private String source;
    private String harUrl;
    private boolean applicationValidationError;
    private boolean databaseLayerInStack;

    /** From {@link com.rca.common.error.StackTraceClassifier}: validation | database | timeout | … */
    private String exceptionKind;
    private BottleneckCategory inferredCategory;
    private String classificationReason;
    private String topApplicationFrame;
    private String stackTraceSummary;
    /** false for validation/auth/app-logic errors where CPU/ES metrics mislead */
    private boolean infraMetricsRelevant;
}
