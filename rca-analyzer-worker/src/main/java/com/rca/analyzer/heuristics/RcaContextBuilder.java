package com.rca.analyzer.heuristics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.model.MetricComparison;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.StructuredFindings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RcaContextBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String buildJson(
            KafkaHarMessage message,
            StructuredFindings findings,
            RcaHeuristicsResult heuristics) {

        Map<String, Object> packet = new LinkedHashMap<>();
        Map<String, Object> classification = new LinkedHashMap<>();
        classification.put("primary", heuristics.getPrimaryCategory().label());
        classification.put("confidence", heuristics.getConfidence());
        classification.put("scores", heuristics.getScores());
        classification.put("sourceAgreement", heuristics.getSourceAgreement() + "/" + heuristics.getSourcesAvailable());
        classification.put("confidenceBreakdown", heuristics.getConfidenceBreakdown());
        packet.put("classification", classification);

        Map<String, Object> har = new LinkedHashMap<>();
        har.put("api", findings.getApi());
        har.put("requestId", findings.getRequestId());
        har.put("dominantPhase", findings.getHarTimeline() != null
                ? findings.getHarTimeline().getDominantPhase() : "");
        har.put("timings", findings.getHarTimings());
        har.put("issuesTriggered", findings.getHarIssuesTriggered());
        if (findings.getSlowHarEntries() != null && !findings.getSlowHarEntries().isEmpty()) {
            har.put("slowApiCount", findings.getSlowHarEntries().size());
            har.put("slowApis", findings.getSlowHarEntries());
            har.put("selection", findings.getSlowHarSelection());
        }
        if (findings.getHarForensicsFindings() != null && !findings.getHarForensicsFindings().isEmpty()) {
            har.put("forensicsFindings", findings.getHarForensicsFindings());
        }
        packet.put("har", har);

        packet.put("metricComparisons", slimComparisons(findings.getMetricComparisons()));
        packet.put("triggeredRules", heuristics.getRuleHits().stream()
                .filter(h -> h.isTriggered() && !h.isStubSource())
                .map(h -> Map.of(
                        "id", h.getRuleId(),
                        "category", h.getCategory().name(),
                        "strength", h.getScoreContribution(),
                        "evidence", h.getEvidence()))
                .toList());
        packet.put("disambiguation", findings.getDisambiguationNotes());
        packet.put("observabilitySources", findings.getObservabilitySources());
        packet.put("kibanaHighlights", findings.getKibanaHighlights());
        if (findings.getKibanaHighlights() != null
                && findings.getKibanaHighlights().get("exceptionAnalysis") instanceof Map<?, ?> analysis) {
            packet.put("errorAnalysis", analysis);
        }
        packet.put("graylogHighlights", findings.getGraylogHighlights());
        packet.put("grafanaHighlights", findings.getGrafanaHighlights());
        packet.put("podName", findings.getPodName());

        if (message != null) {
            packet.put("responseStatus", message.getResponseStatus());
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(packet);
        } catch (Exception e) {
            return packet.toString();
        }
    }

    private List<Map<String, Object>> slimComparisons(List<MetricComparison> comparisons) {
        if (comparisons == null) {
            return List.of();
        }
        List<Map<String, Object>> slim = new ArrayList<>();
        for (MetricComparison c : comparisons) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", c.getMetric());
            row.put("host", c.getHost());
            row.put("verdict", c.getVerdict());
            row.put("ratio", c.getRatio());
            row.put("delta", c.getDelta());
            row.put("triggered", c.isTriggered());
            row.put("interpretation", c.getInterpretation());
            if (c.getIncident() != null) {
                row.put("incidentP95", c.getIncident().getP95());
                row.put("incidentMean", c.getIncident().getMean());
            }
            if (c.getBaseline() != null) {
                row.put("baselineMedian", c.getBaseline().getMedian());
                row.put("baselineP95", c.getBaseline().getP95());
            }
            slim.add(row);
        }
        return slim;
    }
}
