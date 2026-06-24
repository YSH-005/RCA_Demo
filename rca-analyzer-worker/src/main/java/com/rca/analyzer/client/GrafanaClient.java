package com.rca.analyzer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rca.analyzer.config.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
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
public class GrafanaClient {

    private final ObservabilityProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GrafanaClient(ObservabilityProperties properties) {
        this.properties = properties;
        String baseUrl = properties.getGrafana().getUrl().replaceAll("/$", "");
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Returns metric name → all sample values in the query window.
     */
    public Map<String, List<Double>> fetchTimeSeries(String podName, Instant from, Instant to) {
        if (!configured(podName)) {
            return Map.of();
        }
        try {
            String body = executeQuery(podName, from, to);
            Map<String, List<Double>> series = parseTimeSeries(body);
            log.info("Grafana returned {} metric series for pod={} window={}..{}",
                    series.size(), podName, from, to);
            return series;
        } catch (WebClientResponseException e) {
            log.error("Grafana HTTP {} for pod={}: {}", e.getStatusCode().value(), podName, e.getMessage());
            return Map.of();
        } catch (Exception e) {
            log.error("Grafana fetch failed for pod={}: {}", podName, e.getMessage());
            return Map.of();
        }
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

    private boolean configured(String podName) {
        if (podName == null || podName.isBlank() || "unknown-pod".equals(podName)) {
            log.warn("Skipping Grafana query — pod name not resolved");
            return false;
        }
        if (properties.getGrafana().getApiKey() == null || properties.getGrafana().getApiKey().isBlank()) {
            log.warn("Grafana API key not configured");
            return false;
        }
        if (properties.getGrafana().getDatasourceUid() == null || properties.getGrafana().getDatasourceUid().isBlank()) {
            log.warn("Grafana datasource UID not configured");
            return false;
        }
        return true;
    }

    private String executeQuery(String podName, Instant from, Instant to) throws Exception {
        String flux = properties.getGrafana().getFluxQueryTemplate()
                .replace("{podName}", podName)
                .replace("{from}", from.toString())
                .replace("{to}", to.toString());

        ObjectNode query = objectMapper.createObjectNode();
        query.put("refId", "A");
        ObjectNode datasource = objectMapper.createObjectNode();
        datasource.put("type", properties.getGrafana().getDatasourceType());
        datasource.put("uid", properties.getGrafana().getDatasourceUid());
        query.set("datasource", datasource);
        query.put("query", flux);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("queries", objectMapper.createArrayNode().add(query));
        root.put("from", from.toEpochMilli() + "");
        root.put("to", to.toEpochMilli() + "");

        return webClient.post()
                .uri("/api/ds/query")
                .header("Authorization", authHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(root))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private Map<String, List<Double>> parseTimeSeries(String body) throws Exception {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        JsonNode results = objectMapper.readTree(body).path("results");
        results.fields().forEachRemaining(result -> {
            JsonNode frames = result.getValue().path("frames");
            for (JsonNode frame : frames) {
                extractFrameSeries(frame, series);
            }
        });
        return series;
    }

    private void extractFrameSeries(JsonNode frame, Map<String, List<Double>> series) {
        JsonNode fields = frame.path("schema").path("fields");
        JsonNode values = frame.path("data").path("values");
        if (!fields.isArray() || !values.isArray() || fields.size() < 2) {
            return;
        }

        for (int i = 1; i < fields.size(); i++) {
            String fieldName = normalizeMetricName(fields.get(i).path("name").asText());
            if (fieldName.isBlank()) {
                continue;
            }
            JsonNode column = values.get(i);
            if (column == null || !column.isArray()) {
                continue;
            }
            List<Double> points = new ArrayList<>();
            for (JsonNode point : column) {
                if (point.isNumber()) {
                    points.add(point.asDouble());
                }
            }
            if (!points.isEmpty()) {
                series.merge(fieldName, points, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
            }
        }
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
