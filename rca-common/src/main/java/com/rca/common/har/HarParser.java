package com.rca.common.har;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class HarParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_HALF_WINDOW = Duration.ofMinutes(7).plusSeconds(30);
    private static final List<String> REQUEST_ID_HEADERS = List.of(
            "x-request-id",
            "requestid",
            "x-correlation-id",
            "correlation-id",
            "traceparent",
            "x-b3-traceid"
    );
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ico", ".map"
    );
    /** Priority Sprinklr endpoints for RCA. */
    private static final List<ApiIdentity> PRIORITY_ENDPOINTS = List.of(
            new ApiIdentity("gql", "caseStreamFeed"),
            new ApiIdentity("gql", "universalCases"),
            new ApiIdentity("gql", "paginatedAssociatedMessagesForCase"),
            new ApiIdentity("rest", "/feed")
    );

    private HarParser() {
    }

    public static ParsedHarEntry parse(byte[] harBytes, Instant from, Instant to, long slowThresholdMs) {
        return selectSlow(harBytes, from, to, slowThresholdMs).getPrimary();
    }

    public static JsonNode readRoot(byte[] harBytes) {
        try {
            return MAPPER.readTree(harBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid HAR file: " + e.getMessage(), e);
        }
    }

    public static HarSelectionResult selectSlow(byte[] harBytes, Instant from, Instant to, long slowThresholdMs) {
        return selectSlow(readRoot(harBytes), from, to, slowThresholdMs);
    }

    public static HarSelectionResult selectSlow(JsonNode root, Instant from, Instant to, long slowThresholdMs) {
        try {
            JsonNode entries = root.path("log").path("entries");
            if (!entries.isArray() || entries.isEmpty()) {
                throw new IllegalArgumentException("HAR file has no entries");
            }

            List<ParsedHarEntry> parsedCandidates = StreamSupport.stream(entries.spliterator(), false)
                    .filter(HarParser::isApiLikeEntry)
                    .map(entry -> toParsedEntry(entry, from, to))
                    .collect(Collectors.toCollection(ArrayList::new));

            if (parsedCandidates.isEmpty()) {
                JsonNode fallback = findSlowestEntry(entries, slowThresholdMs);
                parsedCandidates.add(toParsedEntry(fallback, from, to));
            }

            HarSelectionResult result = HarApiSelector.select(parsedCandidates);
            alignQueryWindow(result.getSlowEntries(), from, to);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid HAR file: " + e.getMessage(), e);
        }
    }

    private static void alignQueryWindow(List<ParsedHarEntry> selected, Instant from, Instant to) {
        if (selected.isEmpty()) {
            return;
        }
        Instant minEvent = selected.stream().map(ParsedHarEntry::getEventTime).min(Instant::compareTo).orElseThrow();
        Instant maxEvent = selected.stream().map(ParsedHarEntry::getEventTime).max(Instant::compareTo).orElseThrow();
        Instant windowFrom = from != null ? from : minEvent.minus(DEFAULT_HALF_WINDOW);
        Instant windowTo = to != null ? to : maxEvent.plus(DEFAULT_HALF_WINDOW);
        if (windowFrom.isAfter(windowTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        for (ParsedHarEntry entry : selected) {
            entry.setWindowFrom(windowFrom);
            entry.setWindowTo(windowTo);
        }
    }

    public static boolean isPriorityApi(String apiKind, String apiName, String url) {
        if (PRIORITY_ENDPOINTS.stream().anyMatch(p -> p.kind().equalsIgnoreCase(apiKind)
                && p.name().equalsIgnoreCase(apiName))) {
            return true;
        }
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        return PRIORITY_ENDPOINTS.stream().anyMatch(p -> {
            if ("rest".equalsIgnoreCase(p.kind())) {
                return path.endsWith(p.name().toLowerCase()) || path.contains(p.name().toLowerCase() + "/");
            }
            return "gql".equalsIgnoreCase(p.kind()) && path.contains("/" + p.name().toLowerCase());
        });
    }

    private static ParsedHarEntry toParsedEntry(JsonNode entry, Instant from, Instant to) {
        JsonNode request = entry.path("request");
        JsonNode response = entry.path("response");
        JsonNode timings = entry.path("timings");
        ApiIdentity api = classifyEntry(request);
        Instant eventTime = Instant.parse(entry.path("startedDateTime").asText());
        Instant windowFrom = from != null ? from : eventTime.minus(DEFAULT_HALF_WINDOW);
        Instant windowTo = to != null ? to : eventTime.plus(DEFAULT_HALF_WINDOW);
        String url = request.path("url").asText("");
        long bodySize = response.path("bodySize").asLong(0);
        long contentSize = response.path("content").path("size").asLong(0);
        String contentEncoding = headerValue(response.path("headers"), "content-encoding");
        String cacheHeader = firstHeaderValue(response.path("headers"), "x-cache", "cf-cache-status", "x-cache-status");
        boolean fromCache = isLikelyCached(timings, response);
        return ParsedHarEntry.builder()
                .requestId(resolveRequestId(request, url))
                .url(url)
                .method(request.path("method").asText("GET"))
                .apiKind(api.kind())
                .apiName(api.name())
                .durationMs(Math.round(entry.path("time").asDouble(0)))
                .responseStatus(response.path("status").asInt(0))
                .eventTime(eventTime)
                .windowFrom(windowFrom)
                .windowTo(windowTo)
                .blockedMs(timing(timings, "blocked"))
                .dnsMs(timing(timings, "dns"))
                .connectMs(timing(timings, "connect"))
                .sslMs(timing(timings, "ssl"))
                .sendMs(timing(timings, "send"))
                .waitMs(timing(timings, "wait"))
                .receiveMs(timing(timings, "receive"))
                .responseBodySize(Math.max(bodySize, contentSize))
                .contentEncoding(contentEncoding)
                .cacheHeader(cacheHeader)
                .fromCache(fromCache)
                .build();
    }

    public static List<HarEntrySnapshot> scanEntries(byte[] harBytes) {
        return scanEntries(readRoot(harBytes));
    }

    public static List<HarEntrySnapshot> scanEntries(JsonNode root) {
        JsonNode entries = root.path("log").path("entries");
        if (!entries.isArray()) {
            return List.of();
        }
        List<HarEntrySnapshot> snapshots = new ArrayList<>();
        for (JsonNode entry : entries) {
            snapshots.add(toSnapshot(entry));
        }
        return snapshots;
    }

    private static HarEntrySnapshot toSnapshot(JsonNode entry) {
        JsonNode request = entry.path("request");
        JsonNode response = entry.path("response");
        JsonNode timings = entry.path("timings");
        ApiIdentity api = classifyEntry(request);
        String url = request.path("url").asText("");
        String path = pathOf(url);
        String method = request.path("method").asText("GET");
        String mimeType = response.path("content").path("mimeType").asText("");
        long bodySize = response.path("bodySize").asLong(0);
        long contentSize = response.path("content").path("size").asLong(0);
        boolean staticAsset = STATIC_EXTENSIONS.stream().anyMatch(path::endsWith);
        boolean imageAsset = staticAsset && isImagePath(path, mimeType);
        return HarEntrySnapshot.builder()
                .url(url)
                .path(path)
                .method(method)
                .apiKind(api.kind())
                .apiName(api.name())
                .startedAt(Instant.parse(entry.path("startedDateTime").asText()))
                .durationMs(Math.round(entry.path("time").asDouble(0)))
                .blockedMs(timing(timings, "blocked"))
                .dnsMs(timing(timings, "dns"))
                .connectMs(timing(timings, "connect"))
                .sslMs(timing(timings, "ssl"))
                .sendMs(timing(timings, "send"))
                .waitMs(timing(timings, "wait"))
                .receiveMs(timing(timings, "receive"))
                .status(response.path("status").asInt(0))
                .bodySize(bodySize)
                .contentSize(contentSize)
                .contentEncoding(headerValue(response.path("headers"), "content-encoding"))
                .mimeType(mimeType)
                .cacheHeader(firstHeaderValue(response.path("headers"), "x-cache", "cf-cache-status", "x-cache-status"))
                .redirectUrl(response.path("redirectURL").asText(""))
                .apiLike(isApiLikeEntry(entry))
                .staticAsset(staticAsset)
                .imageAsset(imageAsset)
                .build();
    }

    private static boolean isImagePath(String path, String mimeType) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".png") || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")
                || lowerPath.endsWith(".gif") || lowerPath.endsWith(".webp") || lowerPath.endsWith(".svg")) {
            return true;
        }
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static boolean isLikelyCached(JsonNode timings, JsonNode response) {
        long wait = timing(timings, "wait");
        long receive = timing(timings, "receive");
        long send = timing(timings, "send");
        int status = response.path("status").asInt(0);
        if (status == 304) {
            return true;
        }
        return wait <= 5 && receive <= 5 && send <= 5 && status == 200;
    }

    private static String headerValue(JsonNode headers, String name) {
        if (!headers.isArray()) {
            return "";
        }
        for (JsonNode header : headers) {
            if (name.equalsIgnoreCase(header.path("name").asText(""))) {
                return header.path("value").asText("").trim();
            }
        }
        return "";
    }

    private static String firstHeaderValue(JsonNode headers, String... names) {
        for (String name : names) {
            String value = headerValue(headers, name);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static JsonNode findSlowestEntry(JsonNode entries, long slowThresholdMs) {
        var candidates = StreamSupport.stream(entries.spliterator(), false)
                .filter(HarParser::isApiLikeEntry)
                .toList();

        var priority = candidates.stream()
                .filter(e -> matchesPriorityEndpoint(e.path("request")))
                .filter(e -> slowThresholdMs <= 0 || e.path("time").asDouble(0) >= slowThresholdMs)
                .max(Comparator.comparingDouble(e -> e.path("time").asDouble(0)));

        if (priority.isPresent()) {
            return priority.get();
        }

        var pool = candidates.isEmpty()
                ? StreamSupport.stream(entries.spliterator(), false).toList()
                : candidates;

        return pool.stream()
                .max(Comparator.comparingDouble(e -> e.path("time").asDouble(0)))
                .filter(e -> slowThresholdMs <= 0 || e.path("time").asDouble(0) >= slowThresholdMs)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No HAR entry above slow threshold of " + slowThresholdMs + "ms"));
    }

    private static boolean matchesPriorityEndpoint(JsonNode request) {
        ApiIdentity api = classifyEntry(request);
        return isPriorityApi(api.kind(), api.name(), request.path("url").asText(""));
    }

    public static boolean isApiLikeEntry(JsonNode entry) {
        JsonNode request = entry.path("request");
        String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(method)) {
            return false;
        }

        String url = request.path("url").asText("").toLowerCase(Locale.ROOT);
        String path = pathOf(url);
        if (STATIC_EXTENSIONS.stream().anyMatch(path::endsWith)) {
            return false;
        }

        ApiIdentity api = classifyEntry(request);
        if (!api.kind().isBlank()) {
            return true;
        }

        return path.contains("/graphql")
                || path.contains("/feed")
                || path.contains("/api/")
                || path.contains("/rest/");
    }

    private static ApiIdentity classifyEntry(JsonNode request) {
        String url = request.path("url").asText("");
        String path = pathOf(url).toLowerCase(Locale.ROOT);

        for (ApiIdentity endpoint : PRIORITY_ENDPOINTS) {
            if ("gql".equalsIgnoreCase(endpoint.kind())
                    && path.contains("/" + endpoint.name().toLowerCase())) {
                return endpoint;
            }
        }

        String operationName = extractGqlOperationName(request);
        if (!operationName.isBlank()) {
            return new ApiIdentity("gql", operationName);
        }

        if (path.endsWith("/feed") || path.contains("/feed/")) {
            return new ApiIdentity("rest", "/feed");
        }

        return new ApiIdentity("", "");
    }

    private static String extractGqlOperationName(JsonNode request) {
        String fromBody = extractOperationFromPostData(request.path("postData"));
        if (!fromBody.isBlank()) {
            return fromBody;
        }

        String url = request.path("url").asText("");
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && "operationName".equalsIgnoreCase(kv[0])) {
                        return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private static String extractOperationFromPostData(JsonNode postData) {
        if (postData.isMissingNode()) {
            return "";
        }
        String text = postData.path("text").asText("");
        if (text.isBlank()) {
            return "";
        }
        try {
            JsonNode body = MAPPER.readTree(text);
            String operationName = body.path("operationName").asText("");
            if (!operationName.isBlank()) {
                return operationName;
            }
            JsonNode operations = body.path("operations");
            if (operations.isArray() && !operations.isEmpty()) {
                return operations.get(0).path("operationName").asText("");
            }
        } catch (Exception ignored) {
            int idx = text.indexOf("\"operationName\"");
            if (idx >= 0) {
                int colon = text.indexOf(':', idx);
                int quoteStart = text.indexOf('"', colon + 1);
                int quoteEnd = text.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    return text.substring(quoteStart + 1, quoteEnd);
                }
            }
        }
        return "";
    }

    private static String pathOf(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private static long timing(JsonNode timings, String field) {
        double value = timings.path(field).asDouble(-1);
        return value < 0 ? 0 : Math.round(value);
    }

    private static String resolveRequestId(JsonNode request, String url) {
        for (JsonNode header : request.path("headers")) {
            String name = header.path("name").asText("").toLowerCase(Locale.ROOT);
            for (String candidate : REQUEST_ID_HEADERS) {
                if (candidate.equals(name)) {
                    String value = header.path("value").asText("").trim();
                    if (!value.isBlank()) {
                        return "traceparent".equals(name) ? value.split("-")[1] : value;
                    }
                }
            }
        }

        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && kv[0].equalsIgnoreCase("requestId")) {
                        return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }

        return "unknown";
    }

    private record ApiIdentity(String kind, String name) {
    }
}
