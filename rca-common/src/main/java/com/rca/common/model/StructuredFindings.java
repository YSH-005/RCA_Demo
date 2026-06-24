package com.rca.common.model;

import com.rca.common.enums.BottleneckCategory;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class StructuredFindings {
    private CriticalPathTimeline harTimeline;
    private CriticalPathTimeline backendTimeline;
    private Map<String, Object> harTimings = new LinkedHashMap<>();
    private Map<String, Object> kibanaHighlights = new LinkedHashMap<>();
    private Map<String, Object> graylogHighlights = new LinkedHashMap<>();
    private Map<String, Object> grafanaHighlights = new LinkedHashMap<>();
    private Map<BottleneckCategory, Double> categoryScores = new LinkedHashMap<>();
    private String primaryBottleneck;
    private String requestId;
    private String api;
    private String podName;
    /** kibana=live|miss, graylog=stub|live, grafana=stub|live */
    private Map<String, String> observabilitySources = new LinkedHashMap<>();
    private List<MetricComparison> metricComparisons = new ArrayList<>();
    private List<String> harIssuesTriggered = new ArrayList<>();
    private List<String> disambiguationNotes = new ArrayList<>();
    private Map<String, Object> confidenceBreakdown = new LinkedHashMap<>();
    private List<SlowHarEntry> slowHarEntries = new ArrayList<>();
    private String slowHarSelection;
    private List<HarForensicsFinding> harForensicsFindings = new ArrayList<>();
}
