package com.rca.common.model;

import com.rca.common.har.HarApiTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowHarEntry {
    private String url;
    private String method;
    private String apiKind;
    private String apiName;
    private boolean priority;
    private HarApiTier tier;
    private long durationMs;
    private long waitMs;
    private long blockedMs;
    private long receiveMs;
    private long dnsMs;
    private long connectMs;
    private long sslMs;
    private long sendMs;
    private int responseStatus;
    private long responseBodySize;
    private String contentEncoding;
    private String cacheHeader;
    private boolean fromCache;
}
