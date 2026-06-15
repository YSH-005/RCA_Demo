package com.rca.ingestion.controller;

import com.rca.common.dto.JobCreatedResponse;
import com.rca.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/rca")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<JobCreatedResponse> ingest(@RequestBody Map<String, Object> harPayload) {
        log.info("Received HAR ingestion request");
        JobCreatedResponse response = ingestionService.ingestHar(harPayload);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("rca-ingestion-service is up");
    }
}