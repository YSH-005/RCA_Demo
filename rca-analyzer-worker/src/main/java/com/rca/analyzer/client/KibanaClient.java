package com.rca.analyzer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rca.analyzer.config.ObservabilityProperties;
import com.rca.common.error.ErrorContextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KibanaClient {

    private final ObservabilityProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KibanaClient(ObservabilityProperties properties) {
        this.properties = properties;
    }

    public List<Map<String, Object>> fetchMonitoringLogs(String requestId, Instant from, Instant to) {
        return fetchLogs(requestId, from, to, properties.getKibana().getIndexPattern(), null);
    }

    public List<Map<String, Object>> fetchErrorLogs(String requestId, Instant from, Instant to) {
        String errorIndex = properties.getKibana().getErrorIndexPattern();
        if (errorIndex == null || errorIndex.isBlank()) {
            return List.of();
        }
        return fetchLogs(requestId, from, to, errorIndex, "ERROR");
    }

    public List<Map<String, Object>> fetchBySupportReference(String supportReference, Instant from, Instant to) {
        if (supportReference == null || supportReference.isBlank()) {
            return List.of();
        }
        String errorIndex = properties.getKibana().getErrorIndexPattern();
        String indexPattern = (errorIndex != null && !errorIndex.isBlank())
                ? errorIndex
                : properties.getKibana().getIndexPattern();
        return fetchLogs(supportReference, from, to, indexPattern, null);
    }

    private List<Map<String, Object>> fetchLogsRetry(String requestId, Instant from, Instant to,
                                                     String indexPattern, String levelFilter) {
        int originalSize = properties.getKibana().getMaxResults();
        try {
            properties.getKibana().setMaxResults(Math.min(10, originalSize));
            return fetchLogsOnce(requestId, from, to, indexPattern, levelFilter, false);
        } finally {
            properties.getKibana().setMaxResults(originalSize);
        }
    }

    private List<Map<String, Object>> fetchLogs(String requestId, Instant from, Instant to,
                                                 String indexPattern, String levelFilter) {
        if (properties.getKibana().getUrl() == null || properties.getKibana().getUrl().isBlank()) {
            log.warn("Kibana URL not configured");
            return List.of();
        }
        if (properties.getKibana().getApiKey() == null || properties.getKibana().getApiKey().isBlank()) {
            log.warn("Kibana API key not configured");
            return List.of();
        }
        return fetchLogsOnce(requestId, from, to, indexPattern, levelFilter, true);
    }

    private List<Map<String, Object>> fetchLogsOnce(String requestId, Instant from, Instant to,
                                                    String indexPattern, String levelFilter,
                                                    boolean allowRetry) {
        try {
            String baseUrl = properties.getKibana().getUrl().replaceAll("/$", "");
            String searchPath = indexPattern.replace("*", "%2A") + "%2F_search";
            String proxyUrl = baseUrl + "/api/console/proxy?path=" + searchPath + "&method=POST";

            ObjectNode root = objectMapper.createObjectNode();
            root.put("track_total_hits", false);
            root.put("size", properties.getKibana().getMaxResults());
            root.set("sort", objectMapper.readTree(
                    "[{\"timestamp\":{\"order\":\"desc\",\"unmapped_type\":\"boolean\"}}]"));

            ObjectNode query = objectMapper.createObjectNode();
            ObjectNode bool = objectMapper.createObjectNode();

            ObjectNode multiMatch = objectMapper.createObjectNode();
            multiMatch.put("type", "phrase");
            multiMatch.put("query", requestId);
            multiMatch.put("lenient", true);

            ObjectNode range = objectMapper.createObjectNode();
            ObjectNode timestamp = objectMapper.createObjectNode();
            timestamp.put("format", "strict_date_optional_time");
            timestamp.put("gte", from.toString());
            timestamp.put("lte", to.toString());
            range.set("timestamp", timestamp);

            bool.set("filter", objectMapper.createArrayNode()
                    .add(objectMapper.createObjectNode().set("multi_match", multiMatch))
                    .add(objectMapper.createObjectNode().set("range", range))
                    .addAll(levelFilterClause(levelFilter)));
            query.set("bool", bool);
            root.set("query", query);

            String payload = objectMapper.writeValueAsString(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(proxyUrl))
                    .header("Authorization", apiKeyHeader())
                    .header("kbn-xsrf", "rca-analyzer")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Kibana HTTP {} for requestId={}: {}",
                        response.statusCode(), requestId, truncate(response.body()));
                if (allowRetry && (response.statusCode() == 504 || response.statusCode() == 502
                        || response.statusCode() == 503)) {
                    log.warn("Kibana upstream timeout for requestId={} — retry once with smaller page size", requestId);
                    return fetchLogsRetry(requestId, from, to, indexPattern, levelFilter);
                }
                return List.of();
            }

            String body = response.body();
            if (body.contains("\"error\"")) {
                log.warn("Kibana ES error for requestId={}: {}", requestId, truncate(body));
            }

            List<Map<String, Object>> logs = parseHits(body);
            log.info("Kibana returned {} hits for requestId={} index={}", logs.size(), requestId, indexPattern);
            return logs;
        } catch (Exception e) {
            log.error("Kibana fetch failed for requestId={}: {} ({})",
                    requestId, e.getMessage(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    private com.fasterxml.jackson.databind.node.ArrayNode levelFilterClause(String levelFilter) {
        com.fasterxml.jackson.databind.node.ArrayNode clauses = objectMapper.createArrayNode();
        if (levelFilter == null || levelFilter.isBlank()) {
            return clauses;
        }
        ObjectNode term = objectMapper.createObjectNode();
        term.put("level", levelFilter);
        clauses.add(objectMapper.createObjectNode().set("term", term));
        return clauses;
    }

    private List<Map<String, Object>> parseHits(String body) throws Exception {
        JsonNode hits = objectMapper.readTree(body).path("hits").path("hits");
        List<Map<String, Object>> logs = new ArrayList<>();
        for (JsonNode hit : hits) {
            logs.add(normalizeHit(hit));
        }
        return logs;
    }

    private Map<String, Object> normalizeHit(JsonNode hit) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", "kibana");
        entry.put("index", hit.path("_index").asText());
        entry.put("id", hit.path("_id").asText());

        JsonNode fields = hit.path("fields");
        JsonNode source = hit.path("_source");
        boolean useFields = !fields.isMissingNode() && !fields.isEmpty();

        entry.put("traceId", field(fields, source, useFields, "requestContext.distributedTraceId"));
        entry.put("httpRequestId", field(fields, source, useFields, "requestContext.additional.SPR_HTTP_REQUEST_ID"));
        entry.put("path", field(fields, source, useFields, "requestContext.path"));
        entry.put("method", field(fields, source, useFields, "requestContext.additional.httpMethod"));
        entry.put("gqlOperation", field(fields, source, useFields, "requestContext.additional.GQL_OPERATION_NAME"));
        entry.put("timestamp", field(fields, source, useFields, "timestamp"));
        entry.put("level", field(fields, source, useFields, "level"));
        entry.put("networkTimeMs", toLong(field(fields, source, useFields, "requestContext.additional.REQUEST_RECEIVED_NETWORK_TIME")));
        entry.put("totalExecTimeMs", toLong(field(fields, source, useFields, "attributes.totalExecTime")));
        entry.put("totalWaitTimeMs", toLong(field(fields, source, useFields, "attributes.totalWaitTime")));
        entry.put("esTimeMs", toLong(field(fields, source, useFields, "attributes.esTotalTimeTakenInMillis")));
        entry.put("timedOut", field(fields, source, useFields, "attributes.timedOut"));
        entry.put("success", field(fields, source, useFields, "attributes.success"));
        entry.put("hitsCount", toLong(field(fields, source, useFields, "attributes.hitsCount")));
        entry.put("indicesCount", toLong(field(fields, source, useFields, "attributes.indicesCount")));
        entry.put("podName", podName(field(fields, source, useFields, "hostNameIp")));
        entry.put("deploymentName", field(fields, source, useFields, "deploymentName"));
        entry.put("esHost", field(fields, source, useFields, "attributes.esHost"));
        entry.put("docType", field(fields, source, useFields, "attributes._doc_type"));

        String attributesMsg = field(fields, source, useFields, "attributes.msg");
        if (!attributesMsg.isBlank()) {
            entry.put("attributesMsg", attributesMsg);
            String supportReference = ErrorContextExtractor.extractSupportReference(attributesMsg);
            if (!supportReference.isBlank()) {
                entry.put("supportReference", supportReference);
            }
        }

        String exceptionStackTrace = field(fields, source, useFields, "attributes.__exception_stackTrace");
        if (!exceptionStackTrace.isBlank()) {
            entry.put("exceptionStackTrace", exceptionStackTrace);
            String rootType = ErrorContextExtractor.parseRootExceptionType(exceptionStackTrace);
            if (!rootType.isBlank()) {
                entry.put("exceptionType", rootType);
            }
        }

        if ("ERROR".equalsIgnoreCase(String.valueOf(entry.get("level")))) {
            String existingType = field(fields, source, useFields, "attributes.exceptionType");
            if (!existingType.isBlank()) {
                entry.put("exceptionType", existingType);
            }
            entry.put("exceptionMessage", field(fields, source, useFields, "message"));
        }
        return entry;
    }

    private String field(JsonNode fields, JsonNode source, boolean useFields, String name) {
        if (useFields) {
            JsonNode node = fields.path(name);
            if (node.isArray() && !node.isEmpty()) {
                return node.get(0).asText();
            }
        }
        return nestedText(source, name);
    }

    private String nestedText(JsonNode source, String dottedPath) {
        if (source.isMissingNode()) {
            return "";
        }
        JsonNode node = source;
        for (String part : dottedPath.split("\\.")) {
            node = node.path(part);
        }
        return node.isMissingNode() || node.isNull() ? "" : node.asText();
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

    private String podName(String hostNameIp) {
        if (hostNameIp == null || hostNameIp.isBlank()) {
            return "";
        }
        int slash = hostNameIp.indexOf('/');
        return slash > 0 ? hostNameIp.substring(0, slash) : hostNameIp;
    }

    private String apiKeyHeader() {
        String key = properties.getKibana().getApiKey().trim();
        if (key.contains(":")) {
            String encoded = java.util.Base64.getEncoder().encodeToString(key.getBytes());
            return "ApiKey " + encoded;
        }
        return "ApiKey " + key;
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
