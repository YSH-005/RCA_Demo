package com.rca.common.model;

import lombok.Data;

@Data
public class HarMetadata {
    private String correlationId;
    private SlowestEntry slowestEntry;
}