package com.rca.common.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MetricComparison {
    private String source;
    private String metric;
    private String host;
    private MetricStatSummary incident;
    private MetricStatSummary baseline;
    private double ratio;
    private double delta;
    /** normal | elevated | anomalous_event | insufficient_data */
    private String verdict;
    private String formula;
    private boolean triggered;
    private double strength;
    private String interpretation;
}
