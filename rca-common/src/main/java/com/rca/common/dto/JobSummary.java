package com.rca.common.dto;

import com.rca.common.enums.JobStatus;
import lombok.Data;

@Data
public class JobSummary {
    private String id;
    private JobStatus status;
    private String createdAt;
    private String completedAt;
    private Long processingMs;
    private String faultClassification;
    private Double confidenceScore;
}
