package com.rca.common.har;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.common.error.ErrorContextExtractor;
import com.rca.common.error.ErrorContextMerger;
import com.rca.common.model.ErrorContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Scans HAR API response bodies for Sprinklr user-facing errors and stack traces.
 */
public final class HarErrorExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarErrorExtractor() {
    }

    public static ErrorContext extract(byte[] harBytes) {
        try {
            return extract(MAPPER.readTree(harBytes));
        } catch (Exception e) {
            return empty();
        }
    }

    public static ErrorContext extract(JsonNode root) {
        try {
            JsonNode entries = root.path("log").path("entries");
            if (!entries.isArray()) {
                return empty();
            }

            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode entry : entries) {
                if (!shouldScanEntry(entry)) {
                    continue;
                }
                Candidate candidate = scanEntry(entry);
                if (candidate.hasSignal()) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) {
                return empty();
            }

            candidates.sort(Comparator
                    .comparingInt(Candidate::score).reversed()
                    .thenComparing(c -> c.status >= 400 ? 0 : 1));
            Candidate best = candidates.get(0);
            return toContext(best);
        } catch (Exception e) {
            return empty();
        }
    }

    private static boolean shouldScanEntry(JsonNode entry) {
        if (HarParser.isApiLikeEntry(entry)) {
            return true;
        }
        JsonNode response = entry.path("response");
        if (response.path("status").asInt(0) >= 400) {
            return true;
        }
        String bodyText = response.path("content").path("text").asText("");
        if (bodyText.isBlank()) {
            return false;
        }
        String lower = bodyText.toLowerCase(Locale.ROOT);
        return lower.contains("reference :")
                || lower.contains("oops")
                || lower.contains("exception")
                || lower.contains("__exception_stacktrace");
    }

    private static Candidate scanEntry(JsonNode entry) {
        JsonNode response = entry.path("response");
        JsonNode request = entry.path("request");
        String bodyText = responseText(response);
        String msg = extractMessageField(bodyText);
        String stack = extractStackTraceField(bodyText);
        if (stack.isBlank() && ErrorContextExtractor.hasStackTrace(bodyText)) {
            stack = bodyText;
        }
        String reference = ErrorContextExtractor.extractSupportReference(msg);
        if (reference.isBlank()) {
            reference = ErrorContextExtractor.extractSupportReference(bodyText);
        }
        String exceptionType = ErrorContextExtractor.parseRootExceptionType(stack);
        int status = response.path("status").asInt(0);
        return new Candidate(
                request.path("url").asText(""),
                status,
                msg,
                stack,
                reference,
                exceptionType,
                score(status, msg, stack, reference));
    }

    private static int score(int status, String msg, String stack, String reference) {
        int score = 0;
        if (status >= 400) {
            score += 3;
        }
        if (!reference.isBlank()) {
            score += 5;
        }
        if (!msg.isBlank() && msg.toLowerCase(Locale.ROOT).contains("oops")) {
            score += 4;
        }
        if (ErrorContextExtractor.hasStackTrace(stack)) {
            score += 4;
        } else if (!stack.isBlank()) {
            score += 2;
        }
        return score;
    }

    private static String responseText(JsonNode response) {
        JsonNode content = response.path("content");
        String text = content.path("text").asText("");
        if (!text.isBlank()) {
            return text;
        }
        JsonNode encoding = content.path("encoding");
        if (!encoding.isMissingNode()) {
            return content.path("text").asText("");
        }
        return "";
    }

    private static String extractMessageField(String bodyText) {
        if (bodyText.isBlank()) {
            return "";
        }
        try {
            JsonNode json = MAPPER.readTree(bodyText);
            for (String field : List.of("msg", "message", "errorMessage", "error")) {
                String value = json.path(field).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            JsonNode attributes = json.path("attributes");
            if (!attributes.isMissingNode()) {
                String attrMsg = attributes.path("msg").asText("");
                if (!attrMsg.isBlank()) {
                    return attrMsg;
                }
            }
        } catch (Exception ignored) {
            // plain text body
        }
        if (bodyText.toLowerCase(Locale.ROOT).contains("reference :")) {
            return bodyText.lines()
                    .filter(l -> l.toLowerCase(Locale.ROOT).contains("reference")
                            || l.toLowerCase(Locale.ROOT).contains("oops"))
                    .findFirst()
                    .orElse(bodyText.length() <= 500 ? bodyText : bodyText.substring(0, 500));
        }
        return "";
    }

    private static String extractStackTraceField(String bodyText) {
        if (bodyText.isBlank()) {
            return "";
        }
        try {
            JsonNode json = MAPPER.readTree(bodyText);
            JsonNode attributes = json.path("attributes");
            if (!attributes.isMissingNode()) {
                String stack = attributes.path("__exception_stackTrace").asText("");
                if (!stack.isBlank()) {
                    return stack;
                }
            }
            for (String field : List.of("__exception_stackTrace", "stackTrace", "exceptionStackTrace")) {
                String value = json.path(field).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return ErrorContextExtractor.hasStackTrace(bodyText) ? bodyText : "";
    }

    private static ErrorContext toContext(Candidate candidate) {
        return ErrorContextMerger.enrich(ErrorContext.builder()
                .supportReference(candidate.reference)
                .errorMessage(candidate.msg)
                .exceptionStackTrace(candidate.stack)
                .exceptionType(candidate.exceptionType)
                .source("har")
                .harUrl(candidate.url)
                .build());
    }

    private static ErrorContext empty() {
        return ErrorContext.builder().source("none").build();
    }

    private record Candidate(
            String url,
            int status,
            String msg,
            String stack,
            String reference,
            String exceptionType,
            int score) {

        boolean hasSignal() {
            return score > 0;
        }
    }
}
