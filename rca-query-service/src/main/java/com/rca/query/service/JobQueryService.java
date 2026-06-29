package com.rca.query.service;

import com.rca.common.dto.JobListResponse;
import com.rca.common.dto.JobSummary;
import com.rca.common.enums.FaultType;
import com.rca.common.enums.JobStatus;
import com.rca.common.model.RcaJob;
import com.rca.common.model.RcaReport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final MongoTemplate mongoTemplate;

    public JobListResponse listRecent(int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);

        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(capped);
        query.fields()
                .include("status", "createdAt", "completedAt", "processingMs")
                .include("rcaReport.faultClassification", "rcaReport.confidenceScore");

        JobListResponse response = new JobListResponse();
        response.setTotal(mongoTemplate.count(new Query(), RcaJob.class));
        response.setCompleted(countByStatus(JobStatus.COMPLETED));
        response.setFailed(countByStatus(JobStatus.FAILED));
        response.setInProgress(countByStatuses(JobStatus.PENDING, JobStatus.ANALYZING));
        response.setJobs(mongoTemplate.find(query, RcaJob.class).stream()
                .map(this::toSummary)
                .toList());
        return response;
    }

    private long countByStatus(JobStatus status) {
        return mongoTemplate.count(Query.query(Criteria.where("status").is(status)), RcaJob.class);
    }

    private long countByStatuses(JobStatus... statuses) {
        return mongoTemplate.count(Query.query(Criteria.where("status").in((Object[]) statuses)), RcaJob.class);
    }

    private JobSummary toSummary(RcaJob job) {
        JobSummary summary = new JobSummary();
        summary.setId(job.getId());
        summary.setStatus(job.getStatus());
        summary.setCreatedAt(job.getCreatedAt());
        summary.setCompletedAt(job.getCompletedAt());
        summary.setProcessingMs(job.getProcessingMs());

        RcaReport report = job.getRcaReport();
        if (report != null) {
            FaultType fault = report.getFaultClassification();
            if (fault != null) {
                summary.setFaultClassification(fault.name());
            }
            summary.setConfidenceScore(report.getConfidenceScore());
        }
        return summary;
    }
}
