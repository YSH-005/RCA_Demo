package com.rca.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JobListResponse {
    private long total;
    private long completed;
    private long failed;
    private long inProgress;
    private List<JobSummary> jobs = new ArrayList<>();
}
