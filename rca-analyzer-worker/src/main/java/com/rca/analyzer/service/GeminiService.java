package com.rca.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.common.enums.FaultType;
import com.rca.common.model.RcaReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiService(
            @Value("${gemini.endpoint}") String endpoint,
            @Value("${gemini.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(endpoint)
                .build();
    }

    public RcaReport analyze(String correlationId, String slowestUrl,
                             String slowestMethod, long durationMs,
                             List<String> lokiLogs) {
        String prompt = buildPrompt(correlationId, slowestUrl, slowestMethod, durationMs, lokiLogs);
        log.info("Calling Gemini for correlationId={}", correlationId);

        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    ),
                    "generationConfig", Map.of(
                            "temperature", 0.1,
                            "maxOutputTokens", 4000
                    )
            );

            String rawResponse = webClient.post()
                    .uri("?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseGeminiResponse(rawResponse);

        } catch (Exception e) {
            log.error("Gemini call failed for correlationId={}: {}", correlationId, e.getMessage());
            return fallbackReport(e.getMessage());
        }
    }

    private String buildPrompt(String correlationId, String slowestUrl,
                               String slowestMethod, long durationMs,
                               List<String> lokiLogs) {
        String logsText = lokiLogs.isEmpty()
                ? "No logs available."
                : String.join("\n", lokiLogs.subList(0, Math.min(lokiLogs.size(), 50)));


        return """
        You are an expert site reliability engineer performing root cause analysis.
        
        INCIDENT SUMMARY:
        - Correlation ID: %s
        - Slowest Request: [%s] %s
        - Duration: %d ms
        
        APPLICATION LOGS:
        %s
        
        TASK:
        Analyze the above and respond ONLY with a valid JSON object.
        No markdown, no backticks, no explanation outside the JSON.
        Use plain text only in all string values — no special characters.
        Exact format required:
        {
          "faultClassification": "<DATABASE_BOTTLENECK|CPU_SATURATION|MEMORY_PRESSURE|NETWORK_TIMEOUT|UNKNOWN>",
          "confidenceScore": <0.0 to 1.0>,
          "summary": "<plain text summary, no backticks or special chars>",
          "evidence": ["<point 1>", "<point 2>"],
          "recommendedActions": ["<action 1>", "<action 2>"]
        }
        """.formatted(correlationId, slowestMethod, slowestUrl, durationMs, logsText);
    }

    private RcaReport parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            text = text.replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode json = objectMapper.readTree(text);

            RcaReport report = new RcaReport();
            report.setFaultClassification(
                    FaultType.valueOf(json.path("faultClassification").asText("UNKNOWN"))
            );
            report.setConfidenceScore(json.path("confidenceScore").asDouble(0.5));
            report.setSummary(json.path("summary").asText("No summary available."));

            List<String> evidence = objectMapper.convertValue(
                    json.path("evidence"), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class));
            report.setEvidence(evidence);

            List<String> actions = objectMapper.convertValue(
                    json.path("recommendedActions"), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class));
            report.setRecommendedActions(actions);

            return report;

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return fallbackReport("Parse error: " + e.getMessage());
        }
    }

    private RcaReport fallbackReport(String reason) {
        RcaReport report = new RcaReport();
        report.setFaultClassification(FaultType.UNKNOWN);
        report.setConfidenceScore(0.0);
        report.setSummary("Analysis failed: " + reason);
        report.setEvidence(List.of());
        report.setRecommendedActions(List.of("Retry analysis manually"));
        return report;
    }
}