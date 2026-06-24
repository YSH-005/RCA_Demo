package com.rca.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.analyzer.heuristics.FindingsBuilder;
import com.rca.analyzer.heuristics.RcaContextBuilder;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.StructuredFindings;
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

    public record GeminiSummary(String summary, List<String> recommendedActions) {
    }

    private final WebClient webClient;
    private final String apiKey;
    private final FindingsBuilder findingsBuilder;
    private final RcaContextBuilder contextBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiService(
            @Value("${gemini.endpoint}") String endpoint,
            @Value("${gemini.api-key}") String apiKey,
            FindingsBuilder findingsBuilder,
            RcaContextBuilder contextBuilder
    ) {
        this.apiKey = apiKey;
        this.findingsBuilder = findingsBuilder;
        this.contextBuilder = contextBuilder;
        this.webClient = WebClient.builder()
                .baseUrl(endpoint)
                .build();
    }

    public GeminiSummary summarize(KafkaHarMessage message, StructuredFindings findings,
                                   RcaHeuristicsResult heuristics, List<String> logLines) {
        String prompt = buildPrompt(message, findings, heuristics, logLines);
        log.info("Calling Gemini for correlationId={} bottleneck={}",
                message.getCorrelationId(), heuristics.getPrimaryCategory());

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
            log.error("Gemini call failed for correlationId={}: {}", message.getCorrelationId(), e.getMessage());
            return fallbackSummary(heuristics, findings, e.getMessage());
        }
    }

    private String buildPrompt(KafkaHarMessage message, StructuredFindings findings,
                               RcaHeuristicsResult heuristics, List<String> logLines) {
        String contextJson = contextBuilder.buildJson(message, findings, heuristics);

        String logSample = logLines.isEmpty()
                ? "No additional log snippets."
                : String.join("\n", logLines.subList(0, Math.min(logLines.size(), 12)));

        return """
        You are an expert SRE writing a plain-English RCA summary for engineers.

        CLASSIFICATION IS ALREADY DECIDED — do not change it.
        Use the RCA context packet below. It includes HAR issues, metric incident-vs-baseline
        comparisons, triggered rules with strengths, and disambiguation notes.

        When citing Grafana metrics:
        - Prefer incident vs baseline interpretation from metricComparisons
        - Say when a metric is chronically high but NOT anomalous (verdict=normal, low ratio)
        - Mention confidence drivers (sourceAgreement, category scores)

        Do not mention stub/fake pods (rca-pod-*). Use the real pod name from the packet.

        RCA CONTEXT PACKET:
        %s

        OPTIONAL RAW LOG SNIPPETS:
        %s

        Respond ONLY with valid JSON:
        {
          "summary": "<2-4 sentences referencing dominant HAR phase, key metric comparisons, and backend evidence>",
          "recommendedActions": ["<action 1>", "<action 2>", "<action 3>"]
        }
        """.formatted(contextJson, logSample);
    }

    private GeminiSummary parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            text = text.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode json = objectMapper.readTree(text);

            List<String> actions = objectMapper.convertValue(
                    json.path("recommendedActions"), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class));

            return new GeminiSummary(
                    json.path("summary").asText("No summary available."),
                    actions != null ? actions : List.of()
            );

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return new GeminiSummary("Summary unavailable: " + e.getMessage(), List.of());
        }
    }

    private GeminiSummary fallbackSummary(RcaHeuristicsResult heuristics, StructuredFindings findings,
                                          String reason) {
        String dominant = findings.getHarTimeline() != null && findings.getHarTimeline().getDominantPhase() != null
                ? findings.getHarTimeline().getDominantPhase()
                : "unknown phase";
        String summary = "Rule engine classified this as %s (confidence %.0f%%). Dominant phase: %s. LLM unavailable: %s"
                .formatted(heuristics.getPrimaryCategory().label(),
                        heuristics.getConfidence() * 100, dominant, reason);
        return new GeminiSummary(summary, List.of());
    }
}
