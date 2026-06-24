package com.rca.ingestion.service;

import com.rca.common.dto.JobCreatedResponse;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.JobStatus;
import com.rca.common.har.HarForensicsAnalyzer;
import com.rca.common.har.HarForensicsResult;
import com.rca.common.har.HarParser;
import com.rca.common.har.HarSelectionResult;
import com.rca.common.har.ParsedHarEntry;
import com.rca.common.model.RcaJob;
import com.rca.common.model.SlowHarEntry;
import com.rca.ingestion.repository.RcaJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final RcaJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public JobCreatedResponse analyzeHar(MultipartFile file, Instant from, Instant to, long slowThresholdMs) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("HAR file is required");
        }

        byte[] harBytes;
        try {
            harBytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Failed to read HAR file", e);
        }
        HarSelectionResult selection = HarParser.selectSlow(harBytes, from, to, slowThresholdMs);
        HarForensicsResult forensics = HarForensicsAnalyzer.analyze(harBytes, selection);
        ParsedHarEntry parsed = selection.getPrimary();

        RcaJob job = new RcaJob();
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now().toString());
        job = jobRepository.save(job);

        KafkaHarMessage message = toKafkaMessage(job.getId(), parsed, selection, forensics);
        kafkaTemplate.send("har-ingestion", job.getId(), message);

        log.info("HAR job created: {} requestId={} api={}/{} slowApis={} url={}",
                job.getId(), parsed.getRequestId(), parsed.getApiKind(), parsed.getApiName(),
                selection.getSlowEntries().size(), parsed.getUrl());
        return new JobCreatedResponse(job.getId(), "PENDING", job.getCreatedAt(), "RCA analysis queued");
    }

    private KafkaHarMessage toKafkaMessage(String jobId, ParsedHarEntry parsed, HarSelectionResult selection,
                                           HarForensicsResult forensics) {
        KafkaHarMessage msg = new KafkaHarMessage();
        msg.setJobId(jobId);
        msg.setCorrelationId(parsed.getRequestId());
        msg.setSlowestUrl(parsed.getUrl());
        msg.setSlowestMethod(parsed.getMethod());
        msg.setApiKind(parsed.getApiKind());
        msg.setApiName(parsed.getApiName());
        msg.setDurationMs(parsed.getDurationMs());
        msg.setResponseStatus(parsed.getResponseStatus());
        msg.setEventTimestamp(parsed.getEventTime().toString());
        msg.setQueryWindowFrom(parsed.getWindowFrom().toString());
        msg.setQueryWindowTo(parsed.getWindowTo().toString());
        msg.setBlockedMs(parsed.getBlockedMs());
        msg.setDnsMs(parsed.getDnsMs());
        msg.setConnectMs(parsed.getConnectMs());
        msg.setSslMs(parsed.getSslMs());
        msg.setSendMs(parsed.getSendMs());
        msg.setWaitMs(parsed.getWaitMs());
        msg.setReceiveMs(parsed.getReceiveMs());
        msg.setSlowEntrySelection(selection.getSelectionSummary());
        msg.setSlowEntryTotalCandidates(selection.getTotalApiCandidates());
        msg.setSlowEntryAboveThreshold(selection.getAboveThresholdCount());
        if (forensics.getEnrichedSlowEntries() != null && !forensics.getEnrichedSlowEntries().isEmpty()) {
            msg.setSlowEntries(new ArrayList<>(forensics.getEnrichedSlowEntries()));
        } else {
            msg.setSlowEntries(selection.getSlowEntries().stream().map(this::toSlowHarEntry).toList());
        }
        if (forensics.getFindings() != null) {
            msg.setHarForensicsFindings(new ArrayList<>(forensics.getFindings()));
        }
        return msg;
    }

    private SlowHarEntry toSlowHarEntry(ParsedHarEntry entry) {
        return SlowHarEntry.builder()
                .url(entry.getUrl())
                .method(entry.getMethod())
                .apiKind(entry.getApiKind())
                .apiName(entry.getApiName())
                .priority(isPriority(entry))
                .durationMs(entry.getDurationMs())
                .waitMs(entry.getWaitMs())
                .blockedMs(entry.getBlockedMs())
                .receiveMs(entry.getReceiveMs())
                .dnsMs(entry.getDnsMs())
                .connectMs(entry.getConnectMs())
                .sslMs(entry.getSslMs())
                .sendMs(entry.getSendMs())
                .responseStatus(entry.getResponseStatus())
                .responseBodySize(entry.getResponseBodySize())
                .contentEncoding(entry.getContentEncoding())
                .cacheHeader(entry.getCacheHeader())
                .fromCache(entry.isFromCache())
                .build();
    }

    private boolean isPriority(ParsedHarEntry entry) {
        return switch (entry.getApiName()) {
            case "caseStreamFeed", "universalCases", "paginatedAssociatedMessagesForCase", "/feed" -> true;
            default -> false;
        };
    }
}
