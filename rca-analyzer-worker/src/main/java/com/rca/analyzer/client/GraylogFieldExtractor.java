package com.rca.analyzer.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes Sprinklr Graylog access / calltracking field shapes into RCA timing fields.
 */
final class GraylogFieldExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraylogFieldExtractor() {
    }

    static void enrich(Map<String, Object> entry, JsonNode message) {
        mergeMessageJson(entry, message);
        enrichCallTrackingDetail(entry, message.path("callTrackingDetail"));
        deriveStoreTimings(entry);
    }

    private static void mergeMessageJson(Map<String, Object> entry, JsonNode message) {
        String raw = text(message, "message");
        if (raw.isBlank() || raw.charAt(0) != '{') {
            copyTopLevelAccessFields(entry, message);
            return;
        }
        try {
            JsonNode json = MAPPER.readTree(raw);
            copyAccessFields(entry, json);
            if (json.has("callTrackingDetail")) {
                enrichCallTrackingDetail(entry, json.get("callTrackingDetail"));
            }
        } catch (Exception ignored) {
            copyTopLevelAccessFields(entry, message);
        }
    }

    private static void copyTopLevelAccessFields(Map<String, Object> entry, JsonNode message) {
        copyAccessFields(entry, message);
    }

    private static void copyAccessFields(Map<String, Object> entry, JsonNode json) {
        if ("access".equalsIgnoreCase(text(json, "type"))) {
            entry.put("logKind", "access");
        }
        putIfPresent(entry, "requestId", firstNonBlank(
                text(json, "request_id"),
                text(json, "REQP-requestId"),
                text(json, "distributedTraceId"),
                text(json, "header_x-request-id"),
                text(json, "requestId")));
        putIfPresent(entry, "distributedTraceId", text(json, "distributedTraceId"));
        putIfPresent(entry, "operationName", firstNonBlank(
                text(json, "operationName"), text(json, "REQP-op")));
        putIfPresent(entry, "httpUri", text(json, "http_uri"));
        putIfPresent(entry, "httpMethod", text(json, "http_method"));
        putIfPresent(entry, "status", json.path("status").asInt(0));
        putIfPresent(entry, "dbCallStack", text(json, "dbCallStack"));
        putIfPresent(entry, "perfStatsTree", text(json, "perf-stats"));
        putIfPresent(entry, "perfStatsInferred", text(json, "perf-stats-inferred"));
        putIfPresent(entry, "maxTimeTakenBySlowestStat", toLong(text(json, "maxTimeTakenBySlowestStat")));
        putIfPresent(entry, "maxTimeTakenBy", text(json, "maxTimeTakenBy"));
        putIfPresent(entry, "mongoTimeTaken", toLong(firstNonBlank(
                text(json, "mongo_timeTaken"), text(json, "mongoTimeTaken"))));
        putIfPresent(entry, "mongoTotalCalls", toLong(text(json, "mongo_totalCalls")));
        putIfPresent(entry, "chTimeTaken", toLong(firstNonBlank(
                text(json, "ch_timeTaken"), text(json, "chTimeTaken"))));
        putIfPresent(entry, "totalDbTimeMs", toLong(firstNonBlank(
                text(json, "totalDBTime"), text(json, "totalDbTime"))));
        putIfPresent(entry, "apiTotalMs", toLong(firstNonBlank(
                text(json, "time_taken"), text(json, "api_call_time"), text(json, "timeTaken"))));
        putIfPresent(entry, "networkTimeMs", toLong(firstNonBlank(
                text(json, "request_received_network_time_taken"),
                text(json, "requestReceivedNetworkTime"))));
        putIfPresent(entry, "kafTotalCalls", toLong(text(json, "kaf_totalCalls")));
        putIfPresent(entry, "cpuTimeMs", toLong(text(json, "cpu_time")));
        putIfPresent(entry, "ingressName", firstNonBlank(
                text(json, "ingress_name"), text(json, "ingressName")));
        putIfPresent(entry, "ingressService", firstNonBlank(
                text(json, "service_name"), text(json, "serviceName")));
        putIfPresent(entry, "vhost", text(json, "vhost"));
        putIfPresent(entry, "path", text(json, "path"));
        putIfPresent(entry, "requestTimeMs", parseDurationMs(firstNonBlank(
                text(json, "request_time"), text(json, "requestTime"), text(json, "duration"))));
        putIfPresent(entry, "upstreamResponseTimeMs", parseDurationMs(firstNonBlank(
                text(json, "upstream_response_time"), text(json, "upstreamResponseTime"))));
        String controllerNs = firstNonBlank(text(json, "controller_namespace"), text(json, "controllerNamespace"));
        if (controllerNs.contains("ingress-nginx")) {
            putIfPresent(entry, "ingressNamespace", controllerNs);
        }
    }

    static Long parseDurationMs(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.contains(".")) {
                return Math.round(Double.parseDouble(value) * 1000.0);
            }
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void enrichCallTrackingDetail(Map<String, Object> entry, JsonNode detailNode) {
        if (detailNode.isMissingNode() || detailNode.isNull()) {
            return;
        }
        JsonNode detail = detailNode;
        if (detail.isTextual()) {
            try {
                detail = MAPPER.readTree(detail.asText());
            } catch (Exception e) {
                return;
            }
        }
        Map<String, Object> plainDetail = toPlainMap(detail);
        if (plainDetail != null) {
            entry.put("callTrackingDetail", plainDetail);
        }
        JsonNode trackingByDb = detail.path("trackingByDB");
        if (trackingByDb.isMissingNode()) {
            return;
        }
        JsonNode mongoNode = trackingByDb.path("mongo");
        if (!mongoNode.isMissingNode()) {
            String host = firstMongoHost(mongoNode);
            if (!host.isBlank()) {
                entry.put("mongoHost", host);
            }
            long mongo = mongoNode.path("timeTaken").asLong(0);
            if (mongo > 0) {
                entry.put("mongoTimeTaken", mongo);
            }
        }
        JsonNode esNode = trackingByDb.path("es");
        if (!esNode.isMissingNode()) {
            String host = esNode.path("host").asText("");
            if (!host.isBlank()) {
                entry.put("esHost", host);
            }
            long es = esNode.path("timeTaken").asLong(0);
            if (es > 0) {
                entry.put("esTimeTaken", es);
            }
        }
        long db = trackingByDb.path("db").path("timeTaken").asLong(0);
        if (db > 0) {
            entry.put("dbTimeTaken", db);
        }
    }

    private static String firstMongoHost(JsonNode mongoNode) {
        JsonNode details = mongoNode.path("collectionDetails");
        if (details.isArray() && !details.isEmpty()) {
            return details.get(0).path("v1").path("host").asText("");
        }
        return mongoNode.path("host").asText("");
    }

    private static void deriveStoreTimings(Map<String, Object> entry) {
        long mongo = longVal(entry.get("mongoTimeTaken"));
        long es = longVal(entry.get("esTimeTaken"));
        long ch = longVal(entry.get("chTimeTaken"));
        long db = longVal(entry.get("dbTimeTaken"));
        if (ch > 0 && db == 0) {
            entry.put("dbTimeTaken", ch);
            db = ch;
        }
        long totalDb = longVal(entry.get("totalDbTimeMs"));
        if (totalDb <= 0 && (mongo + es + db) > 0) {
            entry.put("totalDbTimeMs", mongo + es + db);
        }
        if (longVal(entry.get("apiTotalMs")) <= 0 && totalDb > 0) {
            entry.put("apiTotalMs", totalDb + 50);
        }
    }

    static Map<String, Object> toPlainMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode resolved = node;
        if (resolved.isTextual()) {
            try {
                resolved = MAPPER.readTree(resolved.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return MAPPER.convertValue(resolved, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static void putIfPresent(Map<String, Object> entry, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        if (value instanceof Number n && n.longValue() == 0 && !key.equals("status")) {
            return;
        }
        entry.put(key, value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
