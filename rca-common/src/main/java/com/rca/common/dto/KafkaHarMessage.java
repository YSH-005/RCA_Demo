package com.rca.common.dto;

import lombok.Data;

@Data
public class KafkaHarMessage {
    private String jobId;
    private String correlationId;
    private String slowestUrl;
    private String slowestMethod;
    private long durationMs;
    private int responseStatus;
    private String eventTimestamp;
}