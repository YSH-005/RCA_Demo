package com.rca.common.error;

import com.rca.common.model.ErrorContext;

import java.util.List;
import java.util.Map;

/**
 * Merges error signals from HAR and Kibana into a single context for RCA scoring.
 */
public final class ErrorContextMerger {

    private ErrorContextMerger() {
    }

    public static ErrorContext merge(ErrorContext har, List<Map<String, Object>> kibanaLogs) {
        ErrorContext fromKibana = fromKibanaLogs(kibanaLogs);
        if (har == null || isEmpty(har)) {
            return isEmpty(fromKibana) ? empty() : fromKibana;
        }
        if (isEmpty(fromKibana)) {
            return har;
        }

        String supportReference = ErrorContextExtractor.firstNonBlank(
                har.getSupportReference(), fromKibana.getSupportReference());
        String errorMessage = ErrorContextExtractor.firstNonBlank(
                fromKibana.getErrorMessage(), har.getErrorMessage());
        String stack = ErrorContextExtractor.firstNonBlank(
                fromKibana.getExceptionStackTrace(), har.getExceptionStackTrace());
        String exceptionType = ErrorContextExtractor.firstNonBlank(
                fromKibana.getExceptionType(), har.getExceptionType(),
                ErrorContextExtractor.parseRootExceptionType(stack));

        return build(supportReference, errorMessage, stack, exceptionType, "merged", har.getHarUrl());
    }

    public static ErrorContext fromKibanaLogs(List<Map<String, Object>> kibanaLogs) {
        if (kibanaLogs == null || kibanaLogs.isEmpty()) {
            return empty();
        }

        String supportReference = "";
        String errorMessage = "";
        String stack = "";
        String exceptionType = "";

        for (Map<String, Object> log : kibanaLogs) {
            supportReference = ErrorContextExtractor.firstNonBlank(
                    supportReference, str(log.get("supportReference")));
            errorMessage = ErrorContextExtractor.firstNonBlank(
                    errorMessage, str(log.get("attributesMsg")));
            stack = ErrorContextExtractor.firstNonBlank(
                    stack, str(log.get("exceptionStackTrace")));
            exceptionType = ErrorContextExtractor.firstNonBlank(
                    exceptionType, str(log.get("exceptionType")),
                    ErrorContextExtractor.parseRootExceptionType(str(log.get("exceptionStackTrace"))));
        }

        if (supportReference.isBlank() && errorMessage.isBlank() && stack.isBlank() && exceptionType.isBlank()) {
            return empty();
        }

        return build(supportReference, errorMessage, stack, exceptionType, "kibana", null);
    }

    /** Attach stack-trace classification to a partially built error context (e.g. from HAR). */
    public static ErrorContext enrich(ErrorContext ctx) {
        if (ctx == null || isEmpty(ctx)) {
            return ctx != null ? ctx : empty();
        }
        if (ctx.getExceptionKind() != null && !ctx.getExceptionKind().isBlank()) {
            return ctx;
        }
        return build(
                ctx.getSupportReference(),
                ctx.getErrorMessage(),
                ctx.getExceptionStackTrace(),
                ctx.getExceptionType(),
                ctx.getSource(),
                ctx.getHarUrl());
    }

    private static ErrorContext build(
            String supportReference, String errorMessage, String stack,
            String exceptionType, String source, String harUrl) {

        StackTraceClassification classification = StackTraceClassifier.classify(exceptionType, stack);

        return ErrorContext.builder()
                .supportReference(supportReference)
                .errorMessage(errorMessage)
                .exceptionStackTrace(stack)
                .exceptionType(ErrorContextExtractor.firstNonBlank(exceptionType, classification.getExceptionType()))
                .source(source)
                .harUrl(harUrl)
                .applicationValidationError("validation".equals(classification.getKind()))
                .databaseLayerInStack("database".equals(classification.getKind())
                        || ErrorContextExtractor.stackTraceImpliesDatabase(stack))
                .exceptionKind(classification.getKind())
                .inferredCategory(classification.getInferredCategory())
                .classificationReason(classification.getReason())
                .topApplicationFrame(classification.getTopApplicationFrame())
                .stackTraceSummary(classification.getStackTraceSummary())
                .infraMetricsRelevant(classification.isInfraMetricsRelevant())
                .build();
    }

    private static boolean isEmpty(ErrorContext ctx) {
        return ctx == null
                || "none".equals(ctx.getSource())
                || (blank(ctx.getSupportReference()) && blank(ctx.getErrorMessage())
                && blank(ctx.getExceptionStackTrace()) && blank(ctx.getExceptionType()));
    }

    private static ErrorContext empty() {
        return ErrorContext.builder().source("none").infraMetricsRelevant(true).build();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
