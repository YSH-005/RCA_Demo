package com.rca.common.error;

import com.rca.common.enums.BottleneckCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an exception stack trace and infers what kind of problem it is.
 * <p>
 * Order of interpretation:
 * 1. Exception type on the first line (ValidationException, TimeoutException, …)
 * 2. Top application frames (com.spr.*) — where control failed in our code
 * 3. Lower frames — ES/mongo/jdbc/http client hints for infra vs app boundary
 */
public final class StackTraceClassifier {

    private static final Pattern FRAME = Pattern.compile(
            "\\tat\\s+([\\w.$]+)\\.([\\w$]+)\\(([\\w.$]+):(\\d+)\\)");
    private static final Pattern HEAD = Pattern.compile(
            "^([\\w.$]+(?:Exception|Error))\\s*:\\s*(.*)$", Pattern.MULTILINE);

    private StackTraceClassifier() {
    }

    public static StackTraceClassification classify(String exceptionType, String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return classifyTypeOnly(exceptionType);
        }

        String normalized = stackTrace.trim();
        HeadLine head = parseHead(normalized, exceptionType);
        List<Frame> frames = parseFrames(normalized);
        String topAppFrame = firstApplicationFrame(frames);
        String stackSummary = summarizeFrames(frames, 5);

        KindDecision decision = decide(head, frames, topAppFrame);

        return StackTraceClassification.builder()
                .exceptionType(head.type())
                .exceptionMessage(truncate(head.message(), 300))
                .kind(decision.kind())
                .inferredCategory(decision.category())
                .topApplicationFrame(topAppFrame)
                .reason(decision.reason())
                .infraMetricsRelevant(decision.infraMetricsRelevant())
                .stackTraceSummary(stackSummary)
                .build();
    }

    private static StackTraceClassification classifyTypeOnly(String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return empty();
        }
        HeadLine head = new HeadLine(exceptionType, "");
        KindDecision decision = decide(head, List.of(), "");
        return StackTraceClassification.builder()
                .exceptionType(exceptionType)
                .kind(decision.kind())
                .inferredCategory(decision.category())
                .reason(decision.reason())
                .infraMetricsRelevant(decision.infraMetricsRelevant())
                .build();
    }

    private static StackTraceClassification empty() {
        return StackTraceClassification.builder()
                .kind("unknown")
                .inferredCategory(BottleneckCategory.D_EXCEPTION)
                .reason("No stack trace available")
                .infraMetricsRelevant(true)
                .build();
    }

    private static HeadLine parseHead(String stackTrace, String fallbackType) {
        Matcher matcher = HEAD.matcher(stackTrace);
        if (matcher.find()) {
            return new HeadLine(matcher.group(1), matcher.group(2).trim());
        }
        String type = ErrorContextExtractor.firstNonBlank(fallbackType, ErrorContextExtractor.parseRootExceptionType(stackTrace));
        return new HeadLine(type, "");
    }

    private static List<Frame> parseFrames(String stackTrace) {
        List<Frame> frames = new ArrayList<>();
        Matcher matcher = FRAME.matcher(stackTrace);
        while (matcher.find()) {
            frames.add(new Frame(
                    matcher.group(1) + "." + matcher.group(2),
                    matcher.group(1),
                    matcher.group(3),
                    Integer.parseInt(matcher.group(4))));
        }
        return frames;
    }

    private static String firstApplicationFrame(List<Frame> frames) {
        for (Frame frame : frames) {
            if (frame.className().startsWith("com.spr.")) {
                return frame.methodSignature();
            }
        }
        return frames.isEmpty() ? "" : frames.get(0).methodSignature();
    }

    private static String summarizeFrames(List<Frame> frames, int max) {
        if (frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Frame frame : frames) {
            if (count >= max) {
                break;
            }
            if (count > 0) {
                sb.append('\n');
            }
            sb.append("at ").append(frame.methodSignature())
                    .append(" (").append(frame.sourceFile()).append(':').append(frame.line()).append(')');
            count++;
        }
        return sb.toString();
    }

    private static KindDecision decide(HeadLine head, List<Frame> frames, String topAppFrame) {
        String simpleType = simpleName(head.type());
        String allFrames = frames.stream().map(Frame::methodSignature).reduce("", String::concat).toLowerCase(Locale.ROOT);
        String topLower = topAppFrame.toLowerCase(Locale.ROOT);

        if (isValidation(simpleType, topLower, allFrames)) {
            return new KindDecision("validation", BottleneckCategory.D_EXCEPTION, false,
                    "Request rejected at validation layer before backend/DB work — check missing or invalid filters/inputs. "
                            + "Top frame: " + orUnknown(topAppFrame));
        }
        if (isAuth(simpleType, topLower, allFrames)) {
            return new KindDecision("auth", BottleneckCategory.D_EXCEPTION, false,
                    "Authentication/authorization failure — token, session, or permission issue. "
                            + "Top frame: " + orUnknown(topAppFrame));
        }
        if (isTimeout(simpleType, topLower)) {
            return new KindDecision("timeout", BottleneckCategory.A_NETWORK, true,
                    "Timeout waiting for a dependency — check network latency, upstream slowness, or timeout config. "
                            + "Exception: " + simpleType);
        }
        if (isDatabase(simpleType, topLower, allFrames)) {
            return new KindDecision("database", BottleneckCategory.C_DATABASE, true,
                    "Failure inside DB/ES/Mongo layer — check query plan, index health, and ES/Graylog timings. "
                            + "Top frame: " + orUnknown(topAppFrame));
        }
        if (isThreadPool(simpleType, topLower, allFrames)) {
            return new KindDecision("thread_pool", BottleneckCategory.B_BACKEND_CPU, true,
                    "Thread pool saturation or rejected execution — check CPU, queue depth, and Grafana thread metrics.");
        }
        if (isNetwork(simpleType, topLower, allFrames)) {
            return new KindDecision("network", BottleneckCategory.A_NETWORK, true,
                    "Network/connectivity failure calling an external or internal HTTP endpoint.");
        }
        if (isExternal(simpleType, topLower, allFrames)) {
            return new KindDecision("external", BottleneckCategory.A_NETWORK, true,
                    "Downstream external service failure — not local CPU/DB; check partner API health and latency.");
        }
        if (!simpleType.isBlank()) {
            return new KindDecision("application", BottleneckCategory.D_EXCEPTION, false,
                    "Unhandled application exception — inspect top frame and exception message. "
                            + simpleType + " at " + orUnknown(topAppFrame));
        }
        return new KindDecision("unknown", BottleneckCategory.D_EXCEPTION, true,
                "Exception type could not be determined from stack trace");
    }

    private static boolean isValidation(String simpleType, String topFrame, String allFrames) {
        return simpleType.contains("ValidationException")
                || topFrame.contains(".validation.")
                || allFrames.contains("validatemandatory")
                || allFrames.contains("validatelistening");
    }

    private static boolean isAuth(String simpleType, String topFrame, String allFrames) {
        return simpleType.contains("Auth")
                || simpleType.contains("Unauthorized")
                || simpleType.contains("Forbidden")
                || topFrame.contains(".auth.")
                || allFrames.contains("401");
    }

    private static boolean isTimeout(String simpleType, String topFrame) {
        return simpleType.contains("Timeout")
                || simpleType.contains("TimedOut")
                || topFrame.contains("timeout");
    }

    private static boolean isThreadPool(String simpleType, String topFrame, String allFrames) {
        return simpleType.contains("RejectedExecution")
                || allFrames.contains("threadpoolexecutor")
                || allFrames.contains("thread_pool");
    }

    private static boolean isDatabase(String simpleType, String topFrame, String allFrames) {
        return simpleType.contains("Elasticsearch")
                || simpleType.contains("Mongo")
                || simpleType.contains("SQL")
                || simpleType.contains("Jdbc")
                || topFrame.contains(".es.")
                || topFrame.contains("elasticsearch")
                || topFrame.contains("mongo")
                || topFrame.contains("jdbc")
                || topFrame.contains("hibernate")
                || allFrames.contains("esaggregation")
                || allFrames.contains("esquery")
                || ErrorContextExtractor.stackTraceImpliesDatabase(allFrames);
    }

    private static boolean isNetwork(String simpleType, String topFrame, String allFrames) {
        return simpleType.contains("ConnectException")
                || simpleType.contains("SocketException")
                || simpleType.contains("UnknownHost")
                || allFrames.contains("okhttp")
                || allFrames.contains("httpclient")
                || allFrames.contains("resttemplate");
    }

    private static boolean isExternal(String simpleType, String topFrame, String allFrames) {
        return allFrames.contains("microservice")
                || allFrames.contains("httpinvoker")
                || topFrame.contains(".integration.");
    }

    private static String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value != null ? value : "";
        }
        return value.substring(0, max) + "...";
    }

    private record HeadLine(String type, String message) {
    }

    private record Frame(String methodSignature, String className, String sourceFile, int line) {
    }

    private record KindDecision(String kind, BottleneckCategory category, boolean infraMetricsRelevant, String reason) {
    }
}
