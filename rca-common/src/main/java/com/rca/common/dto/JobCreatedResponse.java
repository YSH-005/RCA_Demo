package com.rca.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JobCreatedResponse {
    private String jobId;
    private String status;
    private String createdAt;
    private String message;
}