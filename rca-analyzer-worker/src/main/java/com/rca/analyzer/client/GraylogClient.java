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

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    private final ObservabilityProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraylogClient(ObservabilityProperties properties) {
        this.properties = properties;
        String baseUrl = properties.getGraylog().getUrl().replaceAll("/$", "");
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<Map<String, Object>> fetchLogs(String requestId, Instant from, Instant to) {
        return fetchLogs(requestId, from, to, null);
    }

    public record GraylogFetchResult(
            List<Map<String, Object>> logs,
            boolean authenticated,
            long totalResults) {
        public static GraylogFetchResult unauthenticated() {
            return new GraylogFetchResult(List.of(), false, 0);
        }
    }

    public List<Map<String, Object>> fetchLogs(
            String requestId, Instant from, Instant to, String sessionCookieOverride) {
        return fetchLogsResult(requestId, from, to, sessionCookieOverride).logs();
    }

    public GraylogFetchResult fetchLogsResult(
            String requestId, Instant from, Instant to, String sessionCookieOverride) {
        if (!graylogConfigured(sessionCookieOverride)) {
            log.warn("Graylog not configured (set GRAYLOG_URL and GRAYLOG_TOKEN or GRAYLOG_SESSION_COOKIE)");
            return GraylogFetchResult.unauthenticated();
        }

        try {
            String query = requestIdTextQuery(requestId);
            SearchOutcome outcome = search(requestId, query, from, to, sessionCookieOverride);
            if (!outcome.authenticated()) {
                log.error("Graylog session expired or not authenticated for requestId={}", requestId);
                return GraylogFetchResult.unauthenticated();
            }
            log.info("Graylog returned {} messages for requestId={} query={} (total_results={})",
                    outcome.logs().size(), requestId, query, outcome.totalResults());
            return new GraylogFetchResult(outcome.logs(), true, outcome.totalResults());
        } catch (Exception e) {
            log.error("Graylog fetch failed for requestId={}: {}", requestId, e.getMessage());
            return GraylogFetchResult.unauthenticated();
        }
    }

    private record SearchOutcome(List<Map<String, Object>> logs, boolean authenticated, long totalResults) {
    }

    private SearchOutcome search(
            String requestId, String query, Instant from, Instant to, String sessionCookieOverride) throws Exception {
        AuthMode preferred = hasSessionCookie(sessionCookieOverride) ? AuthMode.SESSION_COOKIE : AuthMode.API_TOKEN;
        SearchOutcome outcome = searchWithAuth(requestId, query, from, to, sessionCookieOverride, preferred);
        if (!outcome.authenticated() && preferred == AuthMode.SESSION_COOKIE && hasApiToken()) {
            log.warn("Graylog session cookie expired or rejected — falling back to API token for requestId={}",
                    requestId);
            return searchWithAuth(requestId, query, from, to, sessionCookieOverride, AuthMode.API_TOKEN);
        }
        return outcome;
    }

    private SearchOutcome searchWithAuth(
            String requestId, String query, Instant from, Instant to,
            String sessionCookieOverride, AuthMode authMode) throws Exception {
        try {
            String body = executeSearchRequest(query, from, to, sessionCookieOverride, authMode);
            if (isHtmlRedirect(body)) {
                return new SearchOutcome(List.of(), false, 0);
            }
            long totalResults = parseTotalResults(body);
            List<Map<String, Object>> logs = parseMessages(body);
            return new SearchOutcome(logs, true, totalResults);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (authMode == AuthMode.SESSION_COOKIE && (status == 401 || status == 302 || status == 403)) {
                return new SearchOutcome(List.of(), false, 0);
            }
            log.error("Graylog HTTP {} for requestId={}: {}", status, requestId, e.getMessage());
            return new SearchOutcome(List.of(), false, 0);
        }
    }

    private enum AuthMode {
        SESSION_COOKIE, API_TOKEN
    }

    private boolean hasSessionCookie(String sessionCookieOverride) {
        return resolveSessionCookie(sessionCookieOverride) != null
                && !resolveSessionCookie(sessionCookieOverride).isBlank();
    }

    private boolean hasApiToken() {
        return properties.getGraylog().getToken() != null && !properties.getGraylog().getToken().isBlank();
    }
    private String executeSearchRequest(
            String query, Instant from, Instant to, String sessionCookieOverride, AuthMode authMode) {
        var request = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/search/universal/absolute")
                        .queryParam("query", query)
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .queryParam("limit", properties.getGraylog().getMaxResults())
                        .build())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        request = applyAuth(request, sessionCookieOverride, authMode);
        return request.retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private long parseTotalResults(String body) throws Exception {
        JsonNode total = objectMapper.readTree(body).path("total_results");
        return total.isMissingNode() || total.isNull() ? 0 : total.asLong();
    }

    private boolean graylogConfigured(String sessionCookieOverride) {
        if (properties.getGraylog().getUrl() == null || properties.getGraylog().getUrl().isBlank()) {
            return false;
        }
        if (sessionCookieOverride != null && !sessionCookieOverride.isBlank()) {
            return true;
        }
        return properties.graylogConfigured();
    }

    private static boolean isHtmlRedirect(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("<") || trimmed.startsWith("<!");
    }

    private org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec<?> applyAuth(
            org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec<?> request,
            String sessionCookieOverride, AuthMode authMode) {
        if (authMode == AuthMode.SESSION_COOKIE) {
            String sessionCookie = resolveSessionCookie(sessionCookieOverride);
            if (sessionCookie != null && !sessionCookie.isBlank()) {
                String baseUrl = properties.getGraylog().getUrl().replaceAll("/$", "");
                return request
                        .header(HttpHeaders.COOKIE, sessionCookie.trim())
                        .header("X-Requested-By", "XMLHttpRequest")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Graylog-No-Session-Extension", "true")
                        .header(HttpHeaders.ORIGIN, baseUrl)
                        .header(HttpHeaders.REFERER, baseUrl + "/search?q=&rangetype=relative&from=300")
                        .header(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT);
            }
        }
        return request
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .header("X-Requested-By", "rca-analyzer");
    }

    private String resolveSessionCookie(String sessionCookieOverride) {
        if (sessionCookieOverride != null && !sessionCookieOverride.isBlank()) {
            return sessionCookieOverride.trim();
        }
        return properties.getGraylog().getSessionCookie();
    }

    /** Free-text search — matches request_id, Mongo comment dId:, and any other field containing the id. */
    static String requestIdTextQuery(String requestId) {
        return "\"" + requestId + "\"";
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
                text(message, "request_id"),
                text(message, "requestId"),
                text(message, "SPR_HTTP_REQUEST_ID"),
                text(message, "distributedTraceId")));
        entry.put("podName", firstNonBlank(
                text(message, "podName"),
                text(message, "pod_name"),
                text(message, "hostNameIp"),
                text(message, "hostname"),
                text(message, "source")));
        String k8sNs = text(message, "k8s_namespace");
        if (!k8sNs.isBlank()) {
            entry.put("k8sNamespace", k8sNs);
        }
        String ingressName = text(message, "ingress_name");
        if (!ingressName.isBlank()) {
            entry.put("ingressName", ingressName);
        }
        String k8sNode = text(message, "k8s_node");
        if (!k8sNode.isBlank()) {
            entry.put("k8sNode", k8sNode);
        }

        GraylogFieldExtractor.enrich(entry, message);
        enrichIngressControllerFields(entry, message);
        enrichIngressControllerDiagnostic(entry, message);

        if (GrafanaIngressContext.isIngressLog(str(entry.get("logKind")))) {
            entry.put("path", firstNonBlank(text(message, "path"), str(entry.get("httpUri"))));
            Long requestMs = GraylogFieldExtractor.parseDurationMs(firstNonBlank(
                    text(message, "request_time"), text(message, "requestTime")));
            if (requestMs != null) {
                entry.put("requestTimeMs", requestMs);
            }
            Long upstreamMs = GraylogFieldExtractor.parseDurationMs(firstNonBlank(
                    text(message, "upstream_response_time"), text(message, "upstreamResponseTime")));
            if (upstreamMs != null) {
                entry.put("upstreamResponseTimeMs", upstreamMs);
            }
            Long durationMs = GraylogFieldExtractor.parseDurationMs(text(message, "duration"));
            if (durationMs != null) {
                entry.put("durationMs", durationMs);
            }
            String status = text(message, "status");
            if (!status.isBlank()) {
                entry.put("httpStatus", status);
            }
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
            if (!entry.containsKey("callTrackingDetail")) {
                Map<String, Object> callTrackingDetail = GraylogFieldExtractor.toPlainMap(message.path("callTrackingDetail"));
                if (callTrackingDetail != null) {
                    entry.put("callTrackingDetail", callTrackingDetail);
                }
            }
            putIfBlank(entry, "esHost", text(message, "esHost"));
            putIfBlank(entry, "mongoHost", text(message, "mongoHost"));
        }

        return entry;
    }

    private void enrichIngressControllerFields(Map<String, Object> entry, JsonNode message) {
        String hostname = firstNonBlank(
                text(message, "hostname"),
                text(message, "source"),
                str(entry.get("podName")));
        String controllerClass = firstNonBlank(
                text(message, "controller_class"),
                text(message, "controllerClass"),
                str(entry.get("controllerClass")));

        if (!GrafanaIngressContext.isNginxController(hostname)) {
            return;
        }

        entry.put("logKind", "ingress_controller");
        entry.put("controllerPod", hostname);
        putIfBlank(entry, "ingressName", str(entry.get("ingressName")));
        putIfBlank(entry, "ingressNamespace", GrafanaIngressContext.deriveControllerNamespace(hostname));
        String k8sNamespace = firstNonBlank(
                str(entry.get("ingressNamespace")),
                text(message, "k8s_namespace"));
        if (k8sNamespace.contains("ingress-nginx")) {
            entry.put("ingressNamespace", k8sNamespace);
        }
        if (!controllerClass.isBlank()) {
            entry.put("controllerClass", controllerClass);
        }
    }

    private void enrichIngressControllerDiagnostic(Map<String, Object> entry, JsonNode message) {
        String logKind = str(entry.get("logKind"));
        String hostname = firstNonBlank(str(entry.get("controllerPod")), str(entry.get("podName")));
        if (!GrafanaIngressContext.isIngressLog(logKind) && !GrafanaIngressContext.isNginxController(hostname)) {
            return;
        }
        String raw = text(message, "message");
        if (raw.contains("Error loading custom default certificate")) {
            entry.put("ingressControllerError", "tls_certificate_load_failed");
        } else if ("stderr".equalsIgnoreCase(text(message, "stream")) && !raw.isBlank()) {
            entry.put("ingressControllerDiagnostic", raw.length() > 240 ? raw.substring(0, 240) : raw);
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void putIfBlank(Map<String, Object> entry, String key, String value) {
        if (value != null && !value.isBlank() && !entry.containsKey(key)) {
            entry.put(key, value);
        }
    }

    private String detectLogKind(JsonNode message, String stream) {
        String explicit = text(message, "logKind");
        if (!explicit.isBlank()) {
            return explicit;
        }
        if ("access".equalsIgnoreCase(text(message, "type")) || !text(message, "http_uri").isBlank()) {
            return "access";
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
        String rawMessage = text(message, "message");
        if (rawMessage.startsWith("{") && rawMessage.contains("\"type\":\"access\"")) {
            return "access";
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
}
