package com.rca.common.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MetricStatSummary {
    private double mean;
    private double median;
    private double p95;
    private double max;
    private double min;
    private double last;
    private double sum;
    private int sampleCount;
}
