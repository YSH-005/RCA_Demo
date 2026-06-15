package com.rca.common.model;

import com.rca.common.enums.JobStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "jobs")
public class RcaJob {
    @Id
    private String id;
    private JobStatus status;
    private String createdAt;
    private String completedAt;
    private Long processingMs;
    private Telemetry telemetry;
    private RcaReport rcaReport;
    private String errorMessage;
}