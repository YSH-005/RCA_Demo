package com.rca.analyzer.service;

import com.rca.analyzer.repository.RcaJobRepository;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.JobStatus;
import com.rca.common.model.RcaJob;
import com.rca.common.model.Telemetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzerService {

    private final RcaJobRepository jobRepository;
    private final LokiService lokiService;
    private final GeminiService geminiService;

    public void analyze(KafkaHarMessage message) {
        String jobId = message.getJobId();
        long startMs = System.currentTimeMillis();

        log.info("Starting analysis for jobId={} correlationId={}", jobId, message.getCorrelationId());

        // 1. Mark job as ANALYZING
        RcaJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("Job not found in MongoDB: {}", jobId);
            return;
        }

        job.setStatus(JobStatus.ANALYZING);
        jobRepository.save(job);

        try {
            // 2. Build time window (event time ± 5 minutes in nanoseconds for Loki)
            Instant eventTime = Instant.parse(message.getEventTimestamp());
            String windowStart = String.valueOf(eventTime.minusSeconds(300).toEpochMilli() * 1_000_000L);
            String windowEnd   = String.valueOf(eventTime.plusSeconds(300).toEpochMilli() * 1_000_000L);

            // 3. Fetch Loki logs
            List<String> lokiLogs = lokiService.fetchLogs(
                    message.getCorrelationId(), windowStart, windowEnd);
            log.info("Fetched {} log lines from Loki for jobId={}", lokiLogs.size(), jobId);

            // 4. Populate Telemetry
            Telemetry telemetry = new Telemetry();
            telemetry.setQueryWindowStart(windowStart);
            telemetry.setQueryWindowEnd(windowEnd);
            telemetry.setLokiLogs(lokiLogs);
            telemetry.setCpuMetrics(List.of());      // Phase 5+ can populate
            telemetry.setMemoryMetrics(List.of());
            job.setTelemetry(telemetry);

            // 5. Call Gemini
            var report = geminiService.analyze(
                    message.getCorrelationId(),
                    message.getSlowestUrl(),
                    message.getSlowestMethod(),
                    message.getDurationMs(),
                    lokiLogs
            );

            // 6. Save completed job
            job.setRcaReport(report);
            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now().toString());
            job.setProcessingMs(System.currentTimeMillis() - startMs);
            jobRepository.save(job);

            log.info("Analysis COMPLETED for jobId={} fault={} confidence={}",
                    jobId, report.getFaultClassification(), report.getConfidenceScore());

        } catch (Exception e) {
            log.error("Analysis FAILED for jobId={}: {}", jobId, e.getMessage(), e);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now().toString());
            job.setProcessingMs(System.currentTimeMillis() - startMs);
            jobRepository.save(job);
        }
    }
}