package com.rca.ingestion.service;

import com.rca.common.dto.JobCreatedResponse;
import com.rca.common.dto.KafkaHarMessage;
import com.rca.common.enums.JobStatus;
import com.rca.common.model.RcaJob;
import com.rca.ingestion.repository.RcaJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final RcaJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public JobCreatedResponse ingestHar(Map<String, Object> harPayload) {
        RcaJob job = new RcaJob();
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now().toString());
        job = jobRepository.save(job);

        KafkaHarMessage message = buildKafkaMessage(job.getId(), harPayload);
        kafkaTemplate.send("har-ingestion", job.getId(), message);

        log.info("Job created: {}", job.getId());
        return new JobCreatedResponse(job.getId(), "PENDING", job.getCreatedAt(), "RCA job queued");
    }

    private KafkaHarMessage buildKafkaMessage(String jobId, Map<String, Object> har) {
        KafkaHarMessage msg = new KafkaHarMessage();
        msg.setJobId(jobId);
        msg.setCorrelationId((String) har.getOrDefault("correlationId", "unknown"));
        msg.setSlowestUrl((String) har.getOrDefault("slowestUrl", ""));
        msg.setSlowestMethod((String) har.getOrDefault("method", "GET"));
        msg.setDurationMs(((Number) har.getOrDefault("durationMs", 0)).longValue());
        msg.setResponseStatus(((Number) har.getOrDefault("responseStatus", 200)).intValue());
        msg.setEventTimestamp(Instant.now().toString());
        return msg;
    }
}