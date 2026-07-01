package com.rca.ingestion.service;

import com.rca.common.dto.JobCreatedResponse;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.JobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.rca.common.har.HarForensicsAnalyzer;
import com.rca.common.har.HarForensicsResult;
import com.rca.common.har.HarErrorExtractor;
import com.rca.common.har.HarParser;
import com.rca.common.har.HarSelectionResult;
import com.rca.common.har.ParsedHarEntry;
import com.rca.common.model.ErrorContext;
import com.rca.common.model.RcaJob;
import com.rca.common.model.SlowHarEntry;
import com.rca.common.observability.SessionCookieNormalizer;
import com.rca.ingestion.repository.RcaJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final RcaJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public JobCreatedResponse analyzeHar(
            MultipartFile file, Instant from, Instant to, long slowThresholdMs,
            String graylogSessionCookie, String grafanaSessionCookie) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("HAR file is required");
        }

        byte[] harBytes;
        try {
            harBytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Failed to read HAR file", e);
        }
        long parseStartMs = System.currentTimeMillis();
        JsonNode harRoot = HarParser.readRoot(harBytes);
        HarSelectionResult selection = HarParser.selectSlow(harRoot, from, to, slowThresholdMs);
        CompletableFuture<HarForensicsResult> forensicsFuture = CompletableFuture.supplyAsync(
                () -> HarForensicsAnalyzer.analyze(harRoot, selection));
        CompletableFuture<ErrorContext> errorFuture = CompletableFuture.supplyAsync(
                () -> HarErrorExtractor.extract(harRoot));
        HarForensicsResult forensics = forensicsFuture.join();
        ErrorContext harError = errorFuture.join();
        ParsedHarEntry parsed = selection.getPrimary();
        long parseMs = System.currentTimeMillis() - parseStartMs;

        RcaJob job = new RcaJob();
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now().toString());
        job = jobRepository.save(job);

        KafkaHarMessage message = toKafkaMessage(job.getId(), parsed, selection, forensics, harError);
        message.setGraylogSessionCookie(SessionCookieNormalizer.graylog(graylogSessionCookie));
        message.setGrafanaSessionCookie(SessionCookieNormalizer.grafana(grafanaSessionCookie));
        kafkaTemplate.send("har-ingestion", job.getId(), message);

        log.info("HAR job created: {} requestId={} api={}/{} slowApis={} parseMs={} cookieOverrides graylog={} grafana={} url={}",
                job.getId(), parsed.getRequestId(), parsed.getApiKind(), parsed.getApiName(),
                selection.getSlowEntries().size(), parseMs,
                cookieLen(message.getGraylogSessionCookie()),
                cookieLen(message.getGrafanaSessionCookie()),
                parsed.getUrl());
        return new JobCreatedResponse(job.getId(), "PENDING", job.getCreatedAt(), "RCA analysis queued");
    }

    private KafkaHarMessage toKafkaMessage(String jobId, ParsedHarEntry parsed, HarSelectionResult selection,
                                           HarForensicsResult forensics, ErrorContext harError) {
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
        if (harError != null && !"none".equals(harError.getSource())) {
            msg.setHarSupportReference(harError.getSupportReference());
            msg.setHarErrorMessage(harError.getErrorMessage());
            msg.setHarExceptionStackTrace(harError.getExceptionStackTrace());
            msg.setHarExceptionType(harError.getExceptionType());
            msg.setHarErrorUrl(harError.getHarUrl());
        }
        return msg;
    }

    private SlowHarEntry toSlowHarEntry(ParsedHarEntry entry) {
        return SlowHarEntry.builder()
                .requestId(entry.getRequestId())
                .url(entry.getUrl())
                .method(entry.getMethod())
                .apiKind(entry.getApiKind())
                .apiName(entry.getApiName())
                .priority(entry.isPriority())
                .tier(entry.getTier())
                .downloadDominated(entry.isDownloadDominated())
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
                .eventTimestamp(entry.getEventTime() != null ? entry.getEventTime().toString() : null)
                .queryWindowFrom(entry.getWindowFrom() != null ? entry.getWindowFrom().toString() : null)
                .queryWindowTo(entry.getWindowTo() != null ? entry.getWindowTo().toString() : null)
                .build();
    }

    private static int cookieLen(String cookie) {
        return cookie == null || cookie.isBlank() ? 0 : cookie.length();
    }
}
