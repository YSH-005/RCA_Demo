package com.rca.common.model;

import com.rca.common.enums.BottleneckCategory;
import lombok.Data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RcaHeuristicsResult {
    private BottleneckCategory primaryCategory = BottleneckCategory.UNKNOWN;
    private Map<BottleneckCategory, Double> scores = new EnumMap<>(BottleneckCategory.class);
    private List<HeuristicRuleHit> ruleHits = new ArrayList<>();
    private double confidence;
    private List<String> harIssuesTriggered = new ArrayList<>();
    private Map<String, Object> confidenceBreakdown = new LinkedHashMap<>();
    private List<String> disambiguationNotes = new ArrayList<>();
    private int sourceAgreement;
    private int sourcesAvailable;
}
