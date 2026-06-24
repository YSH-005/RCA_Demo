package com.rca.analyzer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.analyzer.config.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GraylogClient {

    private final ObservabilityProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraylogClient(ObservabilityProperties properties) {
        this.properties = properties;
        String baseUrl = properties.getGraylog().getUrl().replaceAll("/$", "");
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<Map<String, Object>> fetchLogs(String requestId, Instant from, Instant to) {
        if (properties.getGraylog().getToken() == null || properties.getGraylog().getToken().isBlank()) {
            log.warn("Graylog token not configured");
            return List.of();
        }

        try {
            String query = buildQuery(requestId);
            String uri = "/api/search/universal/absolute"
                    + "?query=" + urlEncode(query)
                    + "&from=" + urlEncode(from.toString())
                    + "&to=" + urlEncode(to.toString())
                    + "&limit=" + properties.getGraylog().getMaxResults();

            String body = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                    .header("X-Requested-By", "rca-analyzer")
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<Map<String, Object>> logs = parseMessages(body);
            log.info("Graylog returned {} messages for requestId={}", logs.size(), requestId);
            return logs;
        } catch (WebClientResponseException e) {
            log.error("Graylog HTTP {} for requestId={}: {}", e.getStatusCode().value(), requestId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.error("Graylog fetch failed for requestId={}: {}", requestId, e.getMessage());
            return List.of();
        }
    }

    private String buildQuery(String requestId) {
        String streamFilter = properties.getGraylog().getStreamFilter();
        if (streamFilter == null || streamFilter.isBlank()) {
            return "\"" + requestId + "\"";
        }
        return streamFilter + " AND \"" + requestId + "\"";
    }

    private List<Map<String, Object>> parseMessages(String body) throws Exception {
        JsonNode messages = objectMapper.readTree(body).path("messages");
        List<Map<String, Object>> logs = new ArrayList<>();
        for (JsonNode wrapper : messages) {
            logs.add(normalizeMessage(wrapper.path("message")));
        }
        return logs;
    }

    private Map<String, Object> normalizeMessage(JsonNode message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", "graylog");
        entry.put("timestamp", text(message, "timestamp"));
        entry.put("message", text(message, "message"));
        String stream = firstStream(message);
        entry.put("stream", stream);
        entry.put("logKind", detectLogKind(message, stream));
        entry.put("requestId", firstNonBlank(
                text(message, "requestId"),
                text(message, "request_id"),
                text(message, "SPR_HTTP_REQUEST_ID"),
                text(message, "distributedTraceId")));
        entry.put("podName", firstNonBlank(
                text(message, "podName"),
                text(message, "pod_name"),
                text(message, "hostNameIp"),
                text(message, "hostname"),
                text(message, "source")));

        if ("ingress".equals(entry.get("logKind"))) {
            entry.put("path", text(message, "path"));
            entry.put("requestTimeMs", toLong(firstNonBlank(
                    text(message, "request_time"), text(message, "requestTime"))));
            entry.put("upstreamResponseTimeMs", toLong(firstNonBlank(
                    text(message, "upstream_response_time"), text(message, "upstreamResponseTime"))));
            entry.put("durationMs", toLong(text(message, "duration")));
        }

        if ("perfstats".equals(entry.get("logKind"))) {
            entry.put("statName", text(message, "statName"));
            entry.put("fromParent", text(message, "fromParent"));
            entry.put("fromRoot", text(message, "fromRoot"));
            entry.put("stopTimeMs", toLong(text(message, "stopTime")));
            entry.put("timeTakenMs", toLong(firstNonBlank(
                    text(message, "timeTaken"), text(message, "timeTakenMs"))));
        }

        if ("calltracking".equals(entry.get("logKind"))) {
            entry.put("callTrackingDetail", message.path("callTrackingDetail"));
            entry.put("esHost", text(message, "esHost"));
            entry.put("mongoHost", text(message, "mongoHost"));
        }

        entry.put("dbTimeTaken", toLong(firstNonBlank(
                text(message, "dbTimeTaken"),
                text(message, "db_time_taken"),
                text(message, "dbTime"))));
        entry.put("esTimeTaken", toLong(firstNonBlank(
                text(message, "esTimeTaken"),
                text(message, "es_time_taken"),
                text(message, "esTime"))));
        entry.put("mongoTimeTaken", toLong(firstNonBlank(
                text(message, "mongoTimeTaken"),
                text(message, "mongo_time_taken"),
                text(message, "mongoTime"))));
        entry.put("apiTotalMs", toLong(firstNonBlank(
                text(message, "apiTotalMs"),
                text(message, "totalTime"),
                text(message, "total_time"))));
        return entry;
    }

    private String detectLogKind(JsonNode message, String stream) {
        String explicit = text(message, "logKind");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String lowerStream = stream == null ? "" : stream.toLowerCase();
        if (lowerStream.contains("perfstats") || !text(message, "statName").isBlank()) {
            return "perfstats";
        }
        if (lowerStream.contains("calltracking") || message.has("callTrackingDetail")) {
            return "calltracking";
        }
        if (!text(message, "upstream_response_time").isBlank()
                || !text(message, "request_time").isBlank()
                || lowerStream.contains("ingress")) {
            return "ingress";
        }
        if (!text(message, "distributedTraceId").isBlank() || !text(message, "logger").isBlank()) {
            return "app";
        }
        return "";
    }

    private String firstStream(JsonNode message) {
        JsonNode streams = message.path("streams");
        if (streams.isArray() && !streams.isEmpty()) {
            return streams.get(0).asText();
        }
        return firstNonBlank(text(message, "stream"), text(message, "_stream"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String basicAuthHeader() {
        String token = properties.getGraylog().getToken().trim();
        String encoded = java.util.Base64.getEncoder()
                .encodeToString((token + ":token").getBytes());
        return "Basic " + encoded;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
