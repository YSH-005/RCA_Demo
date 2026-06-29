package com.rca.query.controller;

import com.rca.common.dto.JobListResponse;
import com.rca.common.model.RcaJob;
import com.rca.common.model.RcaReport;
import com.rca.query.repository.RcaJobRepository;
import com.rca.query.service.JobQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/rca")
@RequiredArgsConstructor
public class QueryController {

    private final RcaJobRepository jobRepository;
    private final JobQueryService jobQueryService;

    @GetMapping("/jobs")
    public ResponseEntity<JobListResponse> listJobs(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        log.debug("Listing recent jobs limit={}", limit);
        return ResponseEntity.ok(jobQueryService.listRecent(limit));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<RcaJob> getJob(@PathVariable String jobId) {
        log.debug("Fetching job: {}", jobId);
        return jobRepository.findById(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{jobId}/report")
    public ResponseEntity<RcaReport> getReport(@PathVariable String jobId) {
        log.debug("Fetching report for job: {}", jobId);
        return (ResponseEntity<RcaReport>) jobRepository.findById(jobId)
                .map(job -> {
                    if (job.getRcaReport() == null) {
                        return ResponseEntity.<RcaReport>noContent().build();
                    }
                    return ResponseEntity.ok(job.getRcaReport());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("rca-query-service is up");
    }
}