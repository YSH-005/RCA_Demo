package com.rca.common.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class Telemetry {
    private String queryWindowStart;
    private String queryWindowEnd;
    private String eventTimestamp;
    private String requestId;
    private String slowestUrl;
    private String slowestMethod;
    private String harApiKind;
    private String harApiName;
    private Long harDurationMs;
    private Long harWaitMs;
    private Long harBlockedMs;
    private Long harDnsMs;
    private Long harConnectMs;
    private Long harSslMs;
    private Long harSendMs;
    private Long harReceiveMs;
    private Integer harResponseStatus;
    private String podName;
    private List<Map<String, Object>> graylogLogs;
    private List<Map<String, Object>> kibanaLogs;
    private Map<String, Object> podMetrics;
    private List<MetricComparison> metricComparisons = new ArrayList<>();
    private List<SlowHarEntry> slowHarEntries = new ArrayList<>();
    private String slowHarSelection;
    private int slowHarSelectedCount;
    private List<HarForensicsFinding> harForensicsFindings = new ArrayList<>();
    private List<String> logLines;
}
