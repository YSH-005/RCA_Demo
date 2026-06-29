package com.rca.common.dto;

import com.rca.common.model.HarForensicsFinding;
import com.rca.common.model.SlowHarEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KafkaHarMessage {
    private String jobId;
    private String correlationId;
    private String slowestUrl;
    private String slowestMethod;
    private String apiKind;
    private String apiName;
    private long durationMs;
    private int responseStatus;
    private String eventTimestamp;
    private String queryWindowFrom;
    private String queryWindowTo;
    private long blockedMs;
    private long dnsMs;
    private long connectMs;
    private long sslMs;
    private long sendMs;
    private long waitMs;
    private long receiveMs;
    /** Heuristically selected slow APIs from the HAR (includes primary). */
    private List<SlowHarEntry> slowEntries = new ArrayList<>();
    private String slowEntrySelection;
    private int slowEntryTotalCandidates;
    private int slowEntryAboveThreshold;
    /** Session-level HAR forensics (polling, CDN, compression, etc.). */
    private List<HarForensicsFinding> harForensicsFindings = new ArrayList<>();
    /** Error signals extracted from HAR response bodies (support ref, stack trace). */
    private String harSupportReference;
    private String harErrorMessage;
    private String harExceptionStackTrace;
    private String harExceptionType;
    private String harErrorUrl;
    /** Optional per-upload Graylog SSO cookie (overrides GRAYLOG_SESSION_COOKIE from .env). */
    private String graylogSessionCookie;
    /** Optional per-upload Grafana SSO cookie (overrides GRAFANA_SESSION_COOKIE from .env). */
    private String grafanaSessionCookie;
}