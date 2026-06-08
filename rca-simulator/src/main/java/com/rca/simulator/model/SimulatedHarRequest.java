package com.rca.simulator.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SimulatedHarRequest {
    private String correlationId;
    private String slowestUrl;
    private String slowestMethod;
    private long durationMs;
    private int responseStatus;
    private String timestamp;
}