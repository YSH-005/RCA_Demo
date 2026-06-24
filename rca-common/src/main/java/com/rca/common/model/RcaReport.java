package com.rca.common.model;

import com.rca.common.enums.BottleneckCategory;
import com.rca.common.enums.FaultType;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class RcaReport {
    private FaultType faultClassification;
    private BottleneckCategory bottleneckCategory;
    private Map<BottleneckCategory, Double> categoryScores;
    private double confidenceScore;
    private String summary;
    private List<String> evidence;
    private List<String> recommendedActions;
    private List<HeuristicRuleHit> ruleHits;
    private CriticalPathTimeline harTimeline;
    private CriticalPathTimeline backendTimeline;
    private StructuredFindings structuredFindings;
}