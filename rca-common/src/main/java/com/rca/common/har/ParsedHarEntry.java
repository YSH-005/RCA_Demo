package com.rca.common.har;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ParsedHarEntry {
    private String requestId;
    private String url;
    private String method;
    /** gql or rest */
    private String apiKind;
    /** GQL operation name or REST path suffix, e.g. caseStreamFeed or /feed */
    private String apiName;
    private boolean priority;
    private HarApiTier tier;
    /** Large payload with download-dominated timing (forensics hint, not a tier). */
    private boolean downloadDominated;
    private long durationMs;
    private int responseStatus;
    private Instant eventTime;
    private Instant windowFrom;
    private Instant windowTo;
    private long blockedMs;
    private long dnsMs;
    private long connectMs;
    private long sslMs;
    private long sendMs;
    private long waitMs;
    private long receiveMs;
    private long responseBodySize;
    private String contentEncoding;
    private String cacheHeader;
    private boolean fromCache;
}
