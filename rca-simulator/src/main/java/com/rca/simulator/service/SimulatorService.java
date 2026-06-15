package com.rca.simulator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
public class SimulatorService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final Random random = new Random();

    @Value("${simulator.ingestion-url}")
    private String ingestionUrl;

    @Value("${simulator.enabled}")
    private boolean enabled;

    private static final List<String[]> ENDPOINTS = List.of(
            new String[]{"GET",    "https://api.example.com/users"},
            new String[]{"POST",   "https://api.example.com/orders"},
            new String[]{"GET",    "https://api.example.com/products/search"},
            new String[]{"PUT",    "https://api.example.com/inventory/update"},
            new String[]{"GET",    "https://api.example.com/reports/daily"},
            new String[]{"DELETE", "https://api.example.com/sessions/cleanup"},
            new String[]{"POST",   "https://api.example.com/payments/process"}
    );

    private static final List<Object[]> FAULT_SCENARIOS = List.of(
            new Object[]{3000,  6000,  500, "DATABASE_BOTTLENECK"},
            new Object[]{5000,  9000,  503, "CPU_SATURATION"},
            new Object[]{4000,  7000,  504, "NETWORK_TIMEOUT"},
            new Object[]{6000, 12000,  500, "MEMORY_PRESSURE"},
            new Object[]{2000,  4000,  200, "NORMAL_SLOW"}
    );

    @Scheduled(fixedDelayString = "${simulator.interval-ms}")
    public void simulateTraffic() {
        if (!enabled) {
            log.debug("Simulator disabled, skipping.");
            return;
        }

        String[] endpoint = ENDPOINTS.get(random.nextInt(ENDPOINTS.size()));
        Object[] fault = FAULT_SCENARIOS.get(random.nextInt(FAULT_SCENARIOS.size()));

        long durationMs = (int) fault[0] + random.nextInt((int) fault[1] - (int) fault[0]);
        int status = (int) fault[2];
        String scenario = (String) fault[3];

        Map<String, Object> request = Map.of(
                "correlationId", UUID.randomUUID().toString(),
                "slowestMethod", endpoint[0],
                "slowestUrl", endpoint[1],
                "durationMs", durationMs,
                "responseStatus", status,
                "timestamp", Instant.now().toString()
        );

        log.info("Simulating fault [{}] → {} {} {}ms status={}",
                scenario, endpoint[0], endpoint[1], durationMs, status);

        try {
            var response = restTemplate.postForObject(ingestionUrl, request, String.class);
            log.info("Ingestion response: {}", response);
        } catch (Exception e) {
            log.error("Failed to send simulated request: {}", e.getMessage());
        }
    }
}