package com.rca.ingestion.controller;

import com.rca.common.dto.JobCreatedResponse;
import com.rca.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/v1/rca")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public ResponseEntity<JobCreatedResponse> analyze(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") long slowThresholdMs
    ) {
        Instant fromInstant = from != null && !from.isBlank() ? Instant.parse(from) : null;
        Instant toInstant = to != null && !to.isBlank() ? Instant.parse(to) : null;
        JobCreatedResponse response = ingestionService.analyzeHar(file, fromInstant, toInstant, slowThresholdMs);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("rca-ingestion-service is up");
    }
}
