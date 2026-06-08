package com.rca.common.model;

import lombok.Data;

@Data
public class SlowestEntry {
    private String url;
    private String method;
    private long durationMs;
    private int responseStatus;
    private String timestamp;
}