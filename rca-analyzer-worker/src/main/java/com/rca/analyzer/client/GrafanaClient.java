package com.rca.analyzer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.regex.Pattern;

@Slf4j
@Component
public class GrafanaClient {

    private static final Pattern UNRESOLVED = Pattern.compile("\\{[a-zA-Z]+}");

    private final ObservabilityProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GrafanaClient(ObservabilityProperties properties) {
        this.properties = properties;
        String baseUrl = properties.getGrafana().getUrl().replaceAll("/$", "");
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Map<String, List<Double>> fetchTimeSeries(String podName, Instant from, Instant to) {
        return fetchTimeSeries(GrafanaQueryContext.of(podName), from, to, null);
    }

    public Map<String, List<Double>> fetchTimeSeries(GrafanaQueryContext context, Instant from, Instant to) {
        return fetchTimeSeries(context, from, to, null);
    }

    public record GrafanaFetchResult(Map<String, List<Double>> series, boolean authenticated) {
        public static GrafanaFetchResult unauthenticated() {
            return new GrafanaFetchResult(Map.of(), false);
        }
    }

    public Map<String, List<Double>> fetchTimeSeries(
            GrafanaQueryContext context, Instant from, Instant to, String sessionCookieOverride) {
        return fetchTimeSeriesResult(context, from, to, sessionCookieOverride).series();
    }

    public Map<String, List<Double>> fetchTimeSeries(
            GrafanaQueryContext context, Instant from, Instant to,
            String sessionCookieOverride, InfluxScope influxScope) {
        return fetchTimeSeriesResult(context, from, to, sessionCookieOverride, influxScope).series();
    }

    public GrafanaFetchResult fetchTimeSeriesResult(
            GrafanaQueryContext context, Instant from, Instant to, String sessionCookieOverride) {
        return fetchTimeSeriesResult(context, from, to, sessionCookieOverride, InfluxScope.ALL);
    }

    public GrafanaFetchResult fetchTimeSeriesResult(
            GrafanaQueryContext context, Instant from, Instant to,
            String sessionCookieOverride, InfluxScope influxScope) {
        if (!configured(sessionCookieOverride)) {
            return GrafanaFetchResult.unauthenticated();
        }
        try {
            Map<String, List<Double>> series = new LinkedHashMap<>();
            if (influxScope != InfluxScope.MONGO_ONLY) {
                mergeSeries(series, executePrometheusQuery(context, from, to, sessionCookieOverride));
            }
            try {
                mergeSeries(series, executeInfluxQuery(context, from, to, sessionCookieOverride, influxScope));
            } catch (WebClientResponseException e) {
                if (isAuthFailure(e)) {
                    log.error("Grafana auth failed (Influx HTTP {}) for pod={}", e.getStatusCode().value(), context.podName());
                    return GrafanaFetchResult.unauthenticated();
                }
                log.warn("Grafana Influx HTTP {} for pod={}: {}", e.getStatusCode().value(), context.podName(), e.getMessage());
            } catch (Exception e) {
                log.warn("Grafana Influx query failed for pod={}: {}", context.podName(), e.getMessage());
            }
            log.info("Grafana returned {} metric series for pod={} esHost={} mongoHost={} window={}..{}",
                    series.size(), context.podName(), context.esHost(), context.mongoHost(), from, to);
            return new GrafanaFetchResult(series, true);
        } catch (WebClientResponseException e) {
            if (isAuthFailure(e)) {
                log.error("Grafana auth failed (HTTP {}) for pod={}: {}", e.getStatusCode().value(), context.podName(), e.getMessage());
                return GrafanaFetchResult.unauthenticated();
            }
            log.error("Grafana HTTP {} for pod={}: {}", e.getStatusCode().value(), context.podName(), e.getMessage());
            return new GrafanaFetchResult(Map.of(), true);
        } catch (Exception e) {
            log.error("Grafana fetch failed for pod={}: {}", context.podName(), e.getMessage());
            return GrafanaFetchResult.unauthenticated();
        }
    }

    private static boolean isAuthFailure(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 401 || status == 403;
    }

    /** Legacy single-point metrics map (last value per series). */
    public Map<String, Object> fetchPodMetrics(String podName, Instant from, Instant to) {
        Map<String, List<Double>> series = fetchTimeSeries(podName, from, to);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("source", "grafana");
        metrics.put("podName", podName);
        series.forEach((name, values) -> {
            if (!values.isEmpty()) {
                metrics.put(name, values.get(values.size() - 1));
            }
        });
        return metrics;
    }

    private boolean configured() {
        return configured(null);
    }

    private boolean configured(String sessionCookieOverride) {
        if (!hasAuth(sessionCookieOverride)) {
            log.warn("Grafana auth not configured (API key or session cookie)");
            return false;
        }
        if (!properties.getGrafana().hasResolvableDatasource()) {
            log.warn("Grafana datasource UID not configured (set GRAFANA_DATASOURCE_UID or GRAFANA_DS_UID_*)");
            return false;
        }
        return true;
    }

    private boolean hasAuth() {
        return hasAuth(null);
    }

    private boolean hasAuth(String sessionCookieOverride) {
        if (sessionCookieOverride != null && !sessionCookieOverride.isBlank()) {
            return true;
        }
        var grafana = properties.getGrafana();
        return (grafana.getApiKey() != null && !grafana.getApiKey().isBlank())
                || (grafana.getSessionCookie() != null && !grafana.getSessionCookie().isBlank());
    }

    private String resolveSessionCookie(String sessionCookieOverride) {
        if (sessionCookieOverride != null && !sessionCookieOverride.isBlank()) {
            return sessionCookieOverride.trim();
        }
        return properties.getGrafana().getSessionCookie();
    }

    private Map<String, List<Double>> executePrometheusQuery(
            GrafanaQueryContext context, Instant from, Instant to, String sessionCookieOverride)
            throws Exception {
        ObservabilityProperties.Grafana grafana = properties.getGrafana();
        ArrayNode queries = objectMapper.createArrayNode();
        for (Map.Entry<String, ObservabilityProperties.PrometheusMetricQuery> entry : grafana.resolvedMetrics().entrySet()) {
            String expr = substitute(entry.getValue().getExpr(), context);
            if (expr.isBlank() || UNRESOLVED.matcher(expr).find()) {
                log.debug("Skipping Grafana Prometheus metric {} — unresolved placeholders in expr", entry.getKey());
                continue;
            }
            String dsUid = "ingress".equals(entry.getValue().getDatasource())
                    ? grafana.resolveIngressDatasourceUid(context, entry.getValue())
                    : grafana.resolveDatasourceUid(entry.getValue());
            if (dsUid.isBlank()) {
                log.debug("Skipping Grafana Prometheus metric {} — no datasource UID for datasource={}",
                        entry.getKey(), entry.getValue().getDatasource());
                continue;
            }
            ObjectNode query = objectMapper.createObjectNode();
            query.put("refId", entry.getKey());
            query.put("hide", false);
            query.put("editorMode", "code");
            query.put("format", "time_series");
            query.put("range", true);
            query.put("exemplar", false);
            query.put("intervalMs", grafana.getIntervalMs());
            query.put("maxDataPoints", grafana.getMaxDataPoints());
            ObjectNode datasource = objectMapper.createObjectNode();
            datasource.put("type", grafana.getDatasourceType());
            datasource.put("uid", dsUid);
            query.set("datasource", datasource);
            query.put("expr", expr);
            queries.add(query);
        }

        if (queries.isEmpty()) {
            log.debug("No executable Grafana Prometheus queries after substitution");
            return Map.of();
        }

        String body = postQuery(grafana.getQueryPath(), queries, from, to, sessionCookieOverride);
        return parseTimeSeries(body);
    }

    public enum InfluxScope {
        ALL, MONGO_ONLY, ES_ONLY, POD_ONLY
    }

    private Map<String, List<Double>> executeInfluxQuery(
            GrafanaQueryContext context, Instant from, Instant to, String sessionCookieOverride)
            throws Exception {
        return executeInfluxQuery(context, from, to, sessionCookieOverride, InfluxScope.ALL);
    }

    private Map<String, List<Double>> executeInfluxQuery(
            GrafanaQueryContext context, Instant from, Instant to,
            String sessionCookieOverride, InfluxScope influxScope)
            throws Exception {
        ObservabilityProperties.Grafana grafana = properties.getGrafana();
        ArrayNode queries = objectMapper.createArrayNode();
        for (Map.Entry<String, ObservabilityProperties.InfluxMetricQuery> entry : grafana.resolvedInfluxMetrics().entrySet()) {
            ObservabilityProperties.InfluxMetricQuery metric = entry.getValue();
            if (!matchesInfluxScope(metric, influxScope)) {
                continue;
            }
            if (!influxHostResolved(context, metric)) {
                log.debug("Skipping Grafana Influx metric {} — {} host not resolved", entry.getKey(), metric.getHostKey());
                continue;
            }
            String influxQl = substitute(metric.getQuery(), context);
            if (influxQl.isBlank() || UNRESOLVED.matcher(influxQl).find()) {
                log.debug("Skipping Grafana Influx metric {} — unresolved placeholders in query", entry.getKey());
                continue;
            }
            String dsUid = grafana.resolveInfluxDatasourceUid(entry.getValue());
            if (dsUid.isBlank()) {
                log.debug("Skipping Grafana Influx metric {} — no datasource UID for datasource={}",
                        entry.getKey(), entry.getValue().getDatasource());
                continue;
            }
            ObjectNode query = objectMapper.createObjectNode();
            query.put("refId", entry.getKey());
            query.put("hide", false);
            query.put("rawQuery", entry.getValue().isRawQuery());
            query.put("resultFormat", "time_series");
            ObjectNode datasource = objectMapper.createObjectNode();
            datasource.put("type", "influxdb");
            datasource.put("uid", dsUid);
            query.set("datasource", datasource);
            query.put("query", influxQl);
            queries.add(query);
        }

        if (queries.isEmpty()) {
            log.debug("No executable Grafana Influx queries after substitution");
            return Map.of();
        }

        String body = postQuery(grafana.getInfluxQueryPath(), queries, from, to, sessionCookieOverride);
        return parseTimeSeries(body, influxScope == InfluxScope.MONGO_ONLY || influxScope == InfluxScope.ES_ONLY);
    }

    private static boolean matchesInfluxScope(ObservabilityProperties.InfluxMetricQuery metric, InfluxScope scope) {
        String key = hostKey(metric);
        boolean mongoMetric = "mongo".equals(key);
        boolean esMetric = "es".equals(key);
        return switch (scope) {
            case ALL -> true;
            case MONGO_ONLY -> mongoMetric;
            case ES_ONLY -> esMetric;
            case POD_ONLY -> !mongoMetric && !esMetric;
        };
    }

    private static String hostKey(ObservabilityProperties.InfluxMetricQuery metric) {
        return metric.getHostKey() != null ? metric.getHostKey().trim() : "mongo";
    }

    private static boolean influxHostResolved(GrafanaQueryContext context, ObservabilityProperties.InfluxMetricQuery metric) {
        String hostKey = hostKey(metric);
        return switch (hostKey) {
            case "es" -> !context.esHost().isBlank();
            case "mongo" -> !context.mongoHost().isBlank();
            default -> true;
        };
    }

    private String postQuery(String path, ArrayNode queries, Instant from, Instant to, String sessionCookieOverride)
            throws Exception {
        ObservabilityProperties.Grafana grafana = properties.getGrafana();
        ObjectNode root = objectMapper.createObjectNode();
        root.set("queries", queries);
        root.put("from", String.valueOf(from.toEpochMilli()));
        root.put("to", String.valueOf(to.toEpochMilli()));

        var request = webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Grafana-Org-Id", String.valueOf(grafana.getOrgId()));

        String sessionCookie = resolveSessionCookie(sessionCookieOverride);
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            request = request.header(HttpHeaders.COOKIE, sessionCookie);
        } else {
            request = request.header(HttpHeaders.AUTHORIZATION, authHeader());
        }

        return request.bodyValue(objectMapper.writeValueAsString(root))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private static void mergeSeries(Map<String, List<Double>> target, Map<String, List<Double>> source) {
        source.forEach((name, values) -> target.merge(name, values, (a, b) -> {
            a.addAll(b);
            return a;
        }));
    }

    private String substitute(String expr, GrafanaQueryContext context) {
        if (expr == null) {
            return "";
        }
        return expr
                .replace("{podName}", escapeOrKeep(context.podName(), "podName"))
                .replace("{namespace}", escapeOrKeep(context.namespace(), "namespace"))
                .replace("{deploymentId}", escapeOrKeep(context.deploymentId(), "deploymentId"))
                .replace("{deployment}", escapeOrKeep(context.deployment(), "deployment"))
                .replace("{esHost}", esHostForInfluxQuery(context.esHost()))
                .replace("{mongoHost}", mongoHostForInfluxQuery(context.mongoHost()))
                .replace("{controllerPod}", regexOrAll(context.controllerPod(), "controllerPod"))
                .replace("{ingressNamespace}", regexOrAll(context.ingressNamespace(), "ingressNamespace"))
                .replace("{controllerClass}", regexOrAll(context.controllerClass(), "controllerClass"))
                .replace("{ingressName}", regexOrAll(context.ingressName(), "ingressName"));
    }

    /** Grafana $__all → Prometheus regex match-all; otherwise escape literal value. */
    private static String regexOrAll(String value, String token) {
        if (value == null || value.isBlank() || ".*".equals(value) || "$__all".equals(value)) {
            return ".*";
        }
        return escapeOrKeep(value, token);
    }

    private static String escapeOrKeep(String value, String token) {
        if (value == null || value.isBlank()) {
            return "{" + token + "}";
        }
        return escapeRegex(value);
    }

    private static String escapeRegex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("([.\\\\^$|?*+()\\[\\]{}])", "\\\\$1");
    }

    /** Prefix regex for ES Influx host (matches shard suffix from short prefix). */
    private static String esHostForInfluxQuery(String host) {
        if (host == null || host.isBlank()) {
            return "{esHost}";
        }
        return escapeRegex(GrafanaInfluxHostContext.toEsHostPrefix(host));
    }

    /** Prefix regex for mongo Influx host (matches {@code qa6-mongo-core-40-b} from {@code qa6-mongo-core-40}). */
    private static String mongoHostForInfluxQuery(String host) {
        if (host == null || host.isBlank()) {
            return "{mongoHost}";
        }
        return escapeRegex(GrafanaInfluxHostContext.toMongoHostPrefix(host));
    }

    Map<String, List<Double>> parseTimeSeries(String body) throws Exception {
        return parseTimeSeries(body, false);
    }

    Map<String, List<Double>> parseTimeSeries(String body, boolean firstSeriesPerRefId) throws Exception {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        JsonNode results = objectMapper.readTree(body).path("results");
        results.fields().forEachRemaining(result -> {
            String refId = result.getKey();
            JsonNode frames = result.getValue().path("frames");
            for (JsonNode frame : frames) {
                extractFrameSeries(refId, frame, series, firstSeriesPerRefId);
            }
        });
        return series;
    }

    private void extractFrameSeries(
            String refId, JsonNode frame, Map<String, List<Double>> series, boolean firstSeriesPerRefId) {
        JsonNode fields = frame.path("schema").path("fields");
        JsonNode values = frame.path("data").path("values");
        if (!fields.isArray() || !values.isArray() || fields.size() < 2) {
            return;
        }

        String metricName = resolveMetricName(refId, fields);
        if (firstSeriesPerRefId && series.containsKey(metricName)) {
            return;
        }
        for (int i = 1; i < fields.size(); i++) {
            JsonNode column = values.get(i);
            if (column == null || !column.isArray() || column.isEmpty()) {
                continue;
            }
            List<Double> points = new ArrayList<>();
            for (JsonNode point : column) {
                if (point.isNumber()) {
                    points.add(point.asDouble());
                }
            }
            if (!points.isEmpty()) {
                String name = fields.size() > 2
                        ? metricName + "_" + normalizeMetricName(fields.get(i).path("name").asText("series"))
                        : metricName;
                series.merge(name, points, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
            }
        }
    }

    private String resolveMetricName(String refId, JsonNode fields) {
        ObservabilityProperties.Grafana grafana = properties.getGrafana();
        if (grafana.resolvedMetrics().containsKey(refId) || grafana.resolvedInfluxMetrics().containsKey(refId)) {
            return refId;
        }
        String schemaRef = fields.path(0).path("name").asText("");
        if (!schemaRef.isBlank() && !"Time".equalsIgnoreCase(schemaRef)) {
            return normalizeMetricName(schemaRef);
        }
        return normalizeMetricName(refId);
    }

    private String normalizeMetricName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private String authHeader() {
        String key = properties.getGrafana().getApiKey().trim();
        if (key.startsWith("Bearer ") || key.startsWith("ApiKey ")) {
            return key;
        }
        return "Bearer " + key;
    }
}
