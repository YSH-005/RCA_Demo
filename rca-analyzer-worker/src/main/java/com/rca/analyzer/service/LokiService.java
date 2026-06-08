package com.rca.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LokiService {

    private final WebClient webClient;

    public LokiService(
            @Value("${loki.url}") String lokiUrl,
            @Value("${loki.user}") String lokiUser,
            @Value("${loki.password}") String lokiPassword
    ) {
        String credentials = lokiUser + ":" + lokiPassword;
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        this.webClient = WebClient.builder()
                .baseUrl(lokiUrl)
                .defaultHeader("Authorization", basicAuth)
                .build();
    }

    public List<String> fetchLogs(String correlationId, String startTime, String endTime) {
        log.info("Querying Loki for correlationId={} window=[{} -> {}]",
                correlationId, startTime, endTime);

        try {
            Map<?, ?> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/loki/api/v1/query_range")
                            .queryParam("query", String.format("{correlation_id=\"%s\"}", correlationId))
                            .queryParam("start", startTime)
                            .queryParam("end", endTime)
                            .queryParam("limit", "100")
                            .build(true))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return extractLogLines(response);

        } catch (Exception e) {
            log.error("Failed to fetch Loki logs for correlationId={}: {}", correlationId, e.getMessage());
            return List.of("Loki query failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractLogLines(Map<?, ?> response) {
        List<String> lines = new ArrayList<>();
        if (response == null) return lines;

        try {
            Map<?, ?> data = (Map<?, ?>) response.get("data");
            if (data == null) return lines;

            List<?> result = (List<?>) data.get("result");
            if (result == null) return lines;

            for (Object stream : result) {
                Map<?, ?> streamMap = (Map<?, ?>) stream;
                List<?> values = (List<?>) streamMap.get("values");
                if (values == null) continue;
                for (Object entry : values) {
                    List<?> pair = (List<?>) entry;
                    if (pair.size() >= 2) {
                        lines.add((String) pair.get(1));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Loki response: {}", e.getMessage());
        }

        return lines;
    }
}