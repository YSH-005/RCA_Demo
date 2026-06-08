package com.rca.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JobStatusResponse {
    private String jobId;
    private String status;
}