package com.rca.common.model;

import com.rca.common.enums.FaultType;
import lombok.Data;
import java.util.List;

@Data
public class RcaReport {
    private FaultType faultClassification;
    private double confidenceScore;
    private String summary;
    private List<String> evidence;
    private List<String> recommendedActions;
}