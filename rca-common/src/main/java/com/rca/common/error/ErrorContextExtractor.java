package com.rca.common.error;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Sprinklr support references and exception types from Kibana/HAR text.
 */
public final class ErrorContextExtractor {

    /** e.g. 202606260553-19cf95d9-0db2-46a5-b479-67d5e337e0d4 */
    private static final Pattern SUPPORT_REFERENCE = Pattern.compile(
            "(?i)(?:reference\\s*:\\s*)?(\\d{12}-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})");
    private static final Pattern STACK_TRACE_HEAD = Pattern.compile(
            "^([\\w.$]+(?:Exception|Error))\\s*:", Pattern.MULTILINE);

    private ErrorContextExtractor() {
    }

    public static String extractSupportReference(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = SUPPORT_REFERENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static String parseRootExceptionType(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        Matcher matcher = STACK_TRACE_HEAD.matcher(stackTrace.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        String firstLine = stackTrace.lines().findFirst().orElse("").trim();
        int colon = firstLine.indexOf(':');
        if (colon > 0) {
            return firstLine.substring(0, colon).trim();
        }
        return firstLine;
    }

    /** Application/config validation errors — infra metrics are not the root cause. */
    public static boolean isApplicationValidationException(String exceptionType, String stackTrace) {
        String type = firstNonBlank(exceptionType, parseRootExceptionType(stackTrace));
        if (type.isBlank()) {
            return false;
        }
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        return simple.contains("ValidationException")
                || simple.equals("IllegalArgumentException")
                || simple.equals("BadRequestException");
    }

    public static boolean stackTraceImpliesDatabase(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return false;
        }
        String lower = stackTrace.toLowerCase();
        return lower.contains("elasticsearch")
                || lower.contains(".es.")
                || lower.contains("mongo")
                || lower.contains("jdbc")
                || lower.contains("hibernate");
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public static boolean hasStackTrace(String stackTrace) {
        return stackTrace != null && stackTrace.contains("Exception")
                && stackTrace.contains("\tat ");
    }
}
