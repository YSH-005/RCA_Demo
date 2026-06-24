package com.rca.analyzer.service;

import com.rca.analyzer.config.ObservabilityProperties;
import com.rca.analyzer.heuristics.FindingsBuilder;
import com.rca.analyzer.heuristics.RcaHeuristicsService;
import com.rca.analyzer.repository.RcaJobRepository;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.JobStatus;
import com.rca.common.model.RcaHeuristicsResult;
import com.rca.common.model.RcaJob;
import com.rca.common.model.RcaReport;
import com.rca.common.model.StructuredFindings;
import com.rca.common.model.Telemetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzerService {

    private final RcaJobRepository jobRepository;
    private final ObservabilityService observabilityService;
    private final RcaHeuristicsService heuristicsService;
    private final FindingsBuilder findingsBuilder;
    private final GeminiService geminiService;
    private final ObservabilityProperties observabilityProperties;

    public void analyze(KafkaHarMessage message) {
        String jobId = message.getJobId();
        long startMs = System.currentTimeMillis();

        log.info("Starting analysis for jobId={} correlationId={}", jobId, message.getCorrelationId());

        RcaJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("Job not found in MongoDB: {}", jobId);
            return;
        }

        job.setStatus(JobStatus.ANALYZING);
        jobRepository.save(job);

        try {
            Instant windowStart = message.getQueryWindowFrom() != null
                    ? Instant.parse(message.getQueryWindowFrom())
                    : Instant.parse(message.getEventTimestamp()).minusSeconds(450);
            Instant windowEnd = message.getQueryWindowTo() != null
                    ? Instant.parse(message.getQueryWindowTo())
                    : Instant.parse(message.getEventTimestamp()).plusSeconds(450);
            Instant eventTime = Instant.parse(message.getEventTimestamp());

            Telemetry telemetry = observabilityService.collect(
                    message.getCorrelationId(), windowStart, windowEnd,
                    HarStitchContext.from(message), eventTime);
            applyHarTimings(telemetry, message);

            RcaHeuristicsResult heuristics = heuristicsService.analyze(telemetry);
            StructuredFindings findings = findingsBuilder.build(message, telemetry, heuristics);
            log.info("Heuristics for jobId={} primary={} scores={} triggeredRules={}",
                    jobId,
                    heuristics.getPrimaryCategory(),
                    heuristics.getScores(),
                    heuristics.getRuleHits().stream().filter(h -> h.isTriggered() && !h.isStubSource()).count());

            log.info("Collected observability for jobId={} graylog={} kibana={} pod={}",
                    jobId,
                    telemetry.getGraylogLogs() != null ? telemetry.getGraylogLogs().size() : 0,
                    telemetry.getKibanaLogs() != null ? telemetry.getKibanaLogs().size() : 0,
                    telemetry.getPodName());
            job.setTelemetry(telemetry);

            List<String> logLines = nonStubLogLines(telemetry);
            var geminiSummary = geminiService.summarize(message, findings, heuristics, logLines);
            RcaReport report = heuristicsService.toReport(
                    heuristics, findings, geminiSummary.summary(), geminiSummary.recommendedActions());
            applyObservabilityWarnings(report, telemetry);

            job.setRcaReport(report);
            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now().toString());
            job.setProcessingMs(System.currentTimeMillis() - startMs);
            jobRepository.save(job);

            log.info("Analysis COMPLETED for jobId={} bottleneck={} fault={} confidence={}",
                    jobId, report.getBottleneckCategory(), report.getFaultClassification(),
                    report.getConfidenceScore());

        } catch (Exception e) {
            log.error("Analysis FAILED for jobId={}: {}", jobId, e.getMessage(), e);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now().toString());
            job.setProcessingMs(System.currentTimeMillis() - startMs);
            jobRepository.save(job);
        }
    }

    private List<String> nonStubLogLines(Telemetry telemetry) {
        List<String> lines = new ArrayList<>();
        if (telemetry.getKibanaLogs() != null) {
            telemetry.getKibanaLogs().forEach(m -> lines.add("[kibana] " + m));
        }
        if (telemetry.getGraylogLogs() != null) {
            telemetry.getGraylogLogs().forEach(m -> lines.add("[graylog] " + m));
        }
        if (telemetry.getPodMetrics() != null && !telemetry.getPodMetrics().isEmpty()) {
            lines.add("[grafana] " + telemetry.getPodMetrics());
        }
        return lines;
    }

    private void applyHarTimings(Telemetry telemetry, KafkaHarMessage message) {
        telemetry.setRequestId(message.getCorrelationId());
        telemetry.setSlowestUrl(message.getSlowestUrl());
        telemetry.setSlowestMethod(message.getSlowestMethod());
        telemetry.setHarApiKind(message.getApiKind());
        telemetry.setHarApiName(message.getApiName());
        telemetry.setHarDurationMs(message.getDurationMs());
        telemetry.setHarWaitMs(message.getWaitMs());
        telemetry.setHarBlockedMs(message.getBlockedMs());
        telemetry.setHarDnsMs(message.getDnsMs());
        telemetry.setHarConnectMs(message.getConnectMs());
        telemetry.setHarSslMs(message.getSslMs());
        telemetry.setHarSendMs(message.getSendMs());
        telemetry.setHarReceiveMs(message.getReceiveMs());
        telemetry.setHarResponseStatus(message.getResponseStatus());
        if (message.getSlowEntries() != null && !message.getSlowEntries().isEmpty()) {
            telemetry.setSlowHarEntries(new ArrayList<>(message.getSlowEntries()));
            telemetry.setSlowHarSelectedCount(message.getSlowEntries().size());
            telemetry.setSlowHarSelection(message.getSlowEntrySelection());
        }
        if (message.getHarForensicsFindings() != null && !message.getHarForensicsFindings().isEmpty()) {
            telemetry.setHarForensicsFindings(new ArrayList<>(message.getHarForensicsFindings()));
        }
    }

    private void applyObservabilityWarnings(RcaReport report, Telemetry telemetry) {
        if (!observabilityProperties.kibanaConfigured()) {
            return;
        }
        boolean noKibanaHits = telemetry.getKibanaLogs() == null || telemetry.getKibanaLogs().isEmpty();
        if (!noKibanaHits) {
            return;
        }
        List<String> evidence = new ArrayList<>(report.getEvidence());
        evidence.add(0, "No Kibana logs matched requestId in query window — classification uses HAR only.");
        report.setEvidence(evidence);
    }
}
