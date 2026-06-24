package com.rca.common.har;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class HarEntrySnapshot {
    private String url;
    private String path;
    private String method;
    private String apiKind;
    private String apiName;
    private Instant startedAt;
    private long durationMs;
    private long blockedMs;
    private long dnsMs;
    private long connectMs;
    private long sslMs;
    private long sendMs;
    private long waitMs;
    private long receiveMs;
    private int status;
    private long bodySize;
    private long contentSize;
    private String contentEncoding;
    private String mimeType;
    private String cacheHeader;
    private String redirectUrl;
    private boolean apiLike;
    private boolean staticAsset;
    private boolean imageAsset;
}
