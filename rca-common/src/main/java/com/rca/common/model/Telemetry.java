package com.rca.common.model;

import lombok.Data;
import java.util.List;

@Data
public class Telemetry {
    private String queryWindowStart;
    private String queryWindowEnd;
    private List<Double> cpuMetrics;
    private List<Double> memoryMetrics;
    private List<String> lokiLogs;
}