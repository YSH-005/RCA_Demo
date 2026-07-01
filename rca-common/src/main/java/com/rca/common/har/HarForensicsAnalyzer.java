package com.rca.common.har;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rca.common.model.HarForensicsFinding;
import com.rca.common.model.SlowHarEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Session-level HAR forensics: product issues (polling, CDN, compression, etc.)
 * plus per-entry enrichment for slow APIs.
 */
public final class HarForensicsAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long TTFB_MS = 500;
    private static final long DNS_MS = 200;
    private static final double DNS_SHARE = 0.20;
    private static final long CONNECT_MS = 300;
    private static final long SSL_MS = 300;
    private static final long MIN_BODY_FOR_COMPRESSION = 1024;
    private static final long LARGE_IMAGE_BYTES = 200_000;
    private static final int POLLING_MIN_CALLS = 4;
    private static final long POLLING_MAX_GAP_MS = 10_000;
    private static final int AUTH_BURST_MIN = 3;
    private static final long AUTH_BURST_WINDOW_MS = 100;
    private static final int WATERFALL_MIN_CHAIN = 3;
    private static final int SERVER_OVERLOAD_MIN_APIS = 3;

    private HarForensicsAnalyzer() {
    }

    public static HarForensicsResult analyze(byte[] harBytes, HarSelectionResult selection) {
        return analyze(HarParser.readRoot(harBytes), selection);
    }

    public static HarForensicsResult analyze(JsonNode root, HarSelectionResult selection) {
        List<HarEntrySnapshot> entries = HarParser.scanEntries(root);
        List<HarForensicsFinding> findings = new ArrayList<>();
        List<SlowHarEntry> enrichedSlow = enrichSlowEntries(selection.getSlowEntries(), entries);

        detectHighTtfb(findings, enrichedSlow);
        detectServerOverload(findings, entries);
        detectCdnCacheMiss(findings, entries);
        detectNoCompression(findings, entries);
        detectRedirectChains(findings, entries);
        detectPolling(findings, entries);
        detectAuthCascade(findings, entries);
        detectSerialWaterfall(findings, entries);
        detectCorsPreflight(findings, entries);
        detectDnsDelays(findings, entries);
        detectOverFetching(findings, entries);
        detectUnoptimizedImages(findings, entries);
        detectCachedResponses(findings, enrichedSlow);

        return HarForensicsResult.builder()
                .findings(findings.stream().filter(HarForensicsFinding::isTriggered).toList())
                .enrichedSlowEntries(enrichedSlow)
                .build();
    }

    private static List<SlowHarEntry> enrichSlowEntries(List<ParsedHarEntry> slow, List<HarEntrySnapshot> all) {
        Map<String, HarEntrySnapshot> byUrlMethod = new LinkedHashMap<>();
        for (HarEntrySnapshot snap : all) {
            byUrlMethod.putIfAbsent(key(snap.getMethod(), snap.getUrl()), snap);
        }
        List<SlowHarEntry> result = new ArrayList<>();
        for (ParsedHarEntry entry : slow) {
            HarEntrySnapshot snap = byUrlMethod.get(key(entry.getMethod(), entry.getUrl()));
            result.add(toSlowHarEntry(entry, snap));
        }
        return result;
    }

    private static SlowHarEntry toSlowHarEntry(ParsedHarEntry entry, HarEntrySnapshot snap) {
        SlowHarEntry.SlowHarEntryBuilder builder = SlowHarEntry.builder()
                .requestId(entry.getRequestId())
                .url(entry.getUrl())
                .method(entry.getMethod())
                .apiKind(entry.getApiKind())
                .apiName(entry.getApiName())
                .priority(entry.isPriority())
                .tier(entry.getTier())
                .downloadDominated(entry.isDownloadDominated())
                .durationMs(entry.getDurationMs())
                .waitMs(entry.getWaitMs())
                .eventTimestamp(entry.getEventTime() != null ? entry.getEventTime().toString() : null)
                .queryWindowFrom(entry.getWindowFrom() != null ? entry.getWindowFrom().toString() : null)
                .queryWindowTo(entry.getWindowTo() != null ? entry.getWindowTo().toString() : null)
                .blockedMs(entry.getBlockedMs())
                .receiveMs(entry.getReceiveMs())
                .dnsMs(entry.getDnsMs())
                .connectMs(entry.getConnectMs())
                .sslMs(entry.getSslMs())
                .sendMs(entry.getSendMs())
                .responseStatus(entry.getResponseStatus())
                .responseBodySize(entry.getResponseBodySize())
                .contentEncoding(entry.getContentEncoding())
                .cacheHeader(entry.getCacheHeader())
                .fromCache(entry.isFromCache());
        if (snap != null && builder.build().getResponseBodySize() <= 0) {
            builder.responseBodySize(Math.max(snap.getBodySize(), snap.getContentSize()));
            builder.contentEncoding(snap.getContentEncoding());
            builder.cacheHeader(snap.getCacheHeader());
        }
        return builder.build();
    }

    private static void detectHighTtfb(List<HarForensicsFinding> findings, List<SlowHarEntry> slow) {
        for (SlowHarEntry entry : slow) {
            if (entry.getWaitMs() < TTFB_MS) {
                continue;
            }
            findings.add(finding("P01_high_ttfb", "High TTFB",
                    "wait > 500ms on API call",
                    "HAR [%s/%s] wait=%dms total=%dms url=%s".formatted(
                            entry.getApiKind(), entry.getApiName(), entry.getWaitMs(), entry.getDurationMs(), entry.getUrl()),
                    "Check DB query / upstream service; add index or cache",
                    entry.getUrl()));
        }
    }

    private static void detectServerOverload(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        long count = entries.stream()
                .filter(HarEntrySnapshot::isApiLike)
                .filter(e -> e.getWaitMs() >= TTFB_MS)
                .map(e -> e.getApiKind() + "/" + e.getApiName())
                .distinct()
                .count();
        if (count >= SERVER_OVERLOAD_MIN_APIS) {
            findings.add(finding("P02_server_overloaded", "Server overloaded",
                    "High TTFB on multiple endpoints",
                    "%d distinct API endpoints with wait≥%dms".formatted(count, TTFB_MS),
                    "Scale infra; add cache; check CPU/memory on serving pods",
                    null));
        }
    }

    private static void detectCdnCacheMiss(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        long missCount = entries.stream()
                .filter(e -> cacheIndicatesMiss(e.getCacheHeader()))
                .count();
        if (missCount >= 2) {
            findings.add(finding("P03_cdn_cache_miss", "CDN cache miss",
                    "x-cache / cf-cache-status indicates MISS",
                    "%d responses with cache MISS headers".formatted(missCount),
                    "Warm CDN before release; review cache TTL after deploy",
                    null));
        }
    }

    private static void detectNoCompression(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        for (HarEntrySnapshot entry : entries) {
            if (!entry.isApiLike()) {
                continue;
            }
            long size = Math.max(entry.getBodySize(), entry.getContentSize());
            if (size < MIN_BODY_FOR_COMPRESSION) {
                continue;
            }
            if (hasCompression(entry.getContentEncoding())) {
                continue;
            }
            String mime = entry.getMimeType() != null ? entry.getMimeType().toLowerCase(Locale.ROOT) : "";
            if (!mime.contains("json") && !mime.contains("text") && !mime.contains("javascript")) {
                continue;
            }
            findings.add(finding("P04_no_compression", "No compression",
                    "Large response without content-encoding",
                    "API response size=%d bytes, encoding=%s url=%s".formatted(
                            size, blank(entry.getContentEncoding()), entry.getUrl()),
                    "Enable gzip/brotli on server",
                    entry.getUrl()));
        }
    }

    private static void detectRedirectChains(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        for (HarEntrySnapshot entry : entries) {
            if (entry.getStatus() < 300 || entry.getStatus() >= 400) {
                continue;
            }
            int hops = countRedirectHops(entry, entries);
            if (hops >= 2) {
                findings.add(finding("P05_redirect_chain", "Redirect chain",
                        "Multiple 301/302 before final content",
                        "%d redirect hops ending at status=%d url=%s".formatted(hops, entry.getStatus(), entry.getUrl()),
                        "Fix URLs; use HTTPS directly; enable HSTS",
                        entry.getUrl()));
            }
        }
    }

    private static int countRedirectHops(HarEntrySnapshot start, List<HarEntrySnapshot> entries) {
        int hops = 1;
        String next = start.getRedirectUrl();
        int guard = 0;
        while (next != null && !next.isBlank() && guard++ < 10) {
            hops++;
            String target = next;
            next = null;
            for (HarEntrySnapshot e : entries) {
                if (e.getUrl().equals(target) && e.getStatus() >= 300 && e.getStatus() < 400) {
                    next = e.getRedirectUrl();
                    break;
                }
            }
        }
        return hops;
    }

    private static void detectPolling(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        Map<String, List<HarEntrySnapshot>> byPath = entries.stream()
                .filter(HarEntrySnapshot::isApiLike)
                .collect(Collectors.groupingBy(HarEntrySnapshot::getPath));
        for (Map.Entry<String, List<HarEntrySnapshot>> group : byPath.entrySet()) {
            List<HarEntrySnapshot> calls = group.getValue().stream()
                    .sorted(Comparator.comparing(HarEntrySnapshot::getStartedAt))
                    .toList();
            if (calls.size() < POLLING_MIN_CALLS) {
                continue;
            }
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < calls.size(); i++) {
                gaps.add(Duration.between(calls.get(i - 1).getStartedAt(), calls.get(i).getStartedAt()).toMillis());
            }
            double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
            if (avgGap > 0 && avgGap <= POLLING_MAX_GAP_MS) {
                findings.add(finding("P06_polling", "Polling anti-pattern",
                        "Same API URL repeated every few seconds",
                        "path=%s called %d times, avg gap=%.0fms".formatted(group.getKey(), calls.size(), avgGap),
                        "Use WebSocket or SSE instead of setInterval fetch",
                        calls.get(0).getUrl()));
            }
        }
    }

    private static void detectAuthCascade(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        List<HarEntrySnapshot> unauthorized = entries.stream()
                .filter(e -> e.getStatus() == 401)
                .sorted(Comparator.comparing(HarEntrySnapshot::getStartedAt))
                .toList();
        if (unauthorized.size() < AUTH_BURST_MIN) {
            return;
        }
        Map<Long, Long> bucketCounts = new HashMap<>();
        for (HarEntrySnapshot entry : unauthorized) {
            long bucket = entry.getStartedAt().toEpochMilli() / AUTH_BURST_WINDOW_MS;
            bucketCounts.merge(bucket, 1L, Long::sum);
        }
        long max = bucketCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (max >= AUTH_BURST_MIN) {
            findings.add(finding("P07_auth_cascade", "Auth cascade failure",
                    "Burst of 401s at same timestamp",
                    "%d responses with 401 within %dms window".formatted(max, AUTH_BURST_WINDOW_MS),
                    "Implement token refresh queue before API calls",
                    unauthorized.get(0).getUrl()));
        }
    }

    private static void detectSerialWaterfall(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        List<HarEntrySnapshot> apis = entries.stream()
                .filter(HarEntrySnapshot::isApiLike)
                .sorted(Comparator.comparing(HarEntrySnapshot::getStartedAt))
                .toList();
        int serialChain = 1;
        int maxChain = 1;
        for (int i = 1; i < apis.size(); i++) {
            HarEntrySnapshot prev = apis.get(i - 1);
            HarEntrySnapshot curr = apis.get(i);
            Instant prevEnd = prev.getStartedAt().plusMillis(prev.getDurationMs());
            if (!curr.getStartedAt().isBefore(prevEnd)) {
                serialChain++;
                maxChain = Math.max(maxChain, serialChain);
            } else {
                serialChain = 1;
            }
        }
        if (maxChain >= WATERFALL_MIN_CHAIN) {
            findings.add(finding("P08_serial_waterfall", "Serial request waterfall",
                    "API requests executed one after another",
                    "%d consecutive API calls without overlap".formatted(maxChain),
                    "Preload resources; parallelize independent requests",
                    apis.get(0).getUrl()));
        }
    }

    private static void detectCorsPreflight(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        List<HarEntrySnapshot> options = entries.stream()
                .filter(e -> "OPTIONS".equalsIgnoreCase(e.getMethod()))
                .toList();
        for (HarEntrySnapshot opt : options) {
            boolean followedByApi = entries.stream()
                    .filter(HarEntrySnapshot::isApiLike)
                    .filter(e -> !"OPTIONS".equalsIgnoreCase(e.getMethod()))
                    .anyMatch(e -> e.getPath().equals(opt.getPath())
                            && e.getStartedAt().isAfter(opt.getStartedAt())
                            && Duration.between(opt.getStartedAt(), e.getStartedAt()).toMillis() <= 5000);
            if (followedByApi) {
                findings.add(finding("P09_cors_preflight", "CORS preflight",
                        "OPTIONS before API call on same path",
                        "OPTIONS then API on path=%s".formatted(opt.getPath()),
                        "Set access-control-max-age to 86400 on server",
                        opt.getUrl()));
            }
        }
    }

    private static void detectDnsDelays(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        Map<String, Long> maxDnsByHost = new HashMap<>();
        for (HarEntrySnapshot entry : entries) {
            if (entry.getDnsMs() <= 0) {
                continue;
            }
            String host = hostOf(entry.getUrl());
            maxDnsByHost.merge(host, entry.getDnsMs(), Math::max);
        }
        long slowHosts = maxDnsByHost.values().stream().filter(d -> d >= DNS_MS).count();
        if (slowHosts >= 2) {
            findings.add(finding("P10_dns_delays", "DNS delays",
                    "High dns timing across multiple origins",
                    "%d hosts with dns≥%dms: %s".formatted(
                            slowHosts, DNS_MS, maxDnsByHost.entrySet().stream()
                                    .filter(e -> e.getValue() >= DNS_MS)
                                    .map(e -> e.getKey() + "=" + e.getValue() + "ms")
                                    .collect(Collectors.joining(", "))),
                    "Reduce origin count; add dns-prefetch in HTML",
                    null));
        }
        for (HarEntrySnapshot entry : entries) {
            if (!entry.isApiLike() || entry.getDurationMs() <= 0) {
                continue;
            }
            double dnsShare = (double) entry.getDnsMs() / entry.getDurationMs();
            if (entry.getDnsMs() >= DNS_MS || dnsShare >= DNS_SHARE) {
                findings.add(finding("H02_dns_slow", "DNS slow",
                        "High dns time on API",
                        "HAR dns=%dms (%.0f%% of %dms) url=%s".formatted(
                                entry.getDnsMs(), dnsShare * 100, entry.getDurationMs(), entry.getUrl()),
                        "Check DNS resolver / too many origins",
                        entry.getUrl()));
            }
        }
    }

    private static void detectOverFetching(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        for (HarEntrySnapshot entry : entries) {
            if (!entry.isApiLike()) {
                continue;
            }
            if (HarSelectionPolicy.isEnterpriseHeavyEndpoint(entry.getApiName())) {
                continue;
            }
            long size = Math.max(entry.getBodySize(), entry.getContentSize());
            if (size >= HarSelectionPolicy.LARGE_PAYLOAD_BYTES) {
                findings.add(finding("P11_api_over_fetching", "API over-fetching",
                        "Very large API response body",
                        "response size=%d bytes url=%s".formatted(size, entry.getUrl()),
                        "Use field filtering or GraphQL selection sets",
                        entry.getUrl()));
            }
        }
    }

    private static void detectUnoptimizedImages(List<HarForensicsFinding> findings, List<HarEntrySnapshot> entries) {
        for (HarEntrySnapshot entry : entries) {
            if (!entry.isImageAsset()) {
                continue;
            }
            long size = Math.max(entry.getBodySize(), entry.getContentSize());
            if (size >= LARGE_IMAGE_BYTES) {
                findings.add(finding("P12_unoptimized_images", "Unoptimized images",
                        "Large image transfer size",
                        "image size=%d bytes url=%s".formatted(size, entry.getUrl()),
                        "Resize and serve WebP/AVIF",
                        entry.getUrl()));
            }
        }
    }

    private static void detectCachedResponses(List<HarForensicsFinding> findings, List<SlowHarEntry> slow) {
        for (SlowHarEntry entry : slow) {
            if (!entry.isFromCache()) {
                continue;
            }
            findings.add(finding("H13_cached_response", "Cached response",
                    "API served from browser cache",
                    "HAR [%s/%s] likely cache hit (wait=%dms receive=%dms) url=%s".formatted(
                            entry.getApiKind(), entry.getApiName(), entry.getWaitMs(), entry.getReceiveMs(), entry.getUrl()),
                    "Re-capture HAR with cache disabled for backend RCA",
                    entry.getUrl()));
        }
    }

    private static HarForensicsFinding finding(
            String id, String title, String symptom, String evidence, String recommendation, String url) {
        return HarForensicsFinding.builder()
                .issueId(id)
                .title(title)
                .symptom(symptom)
                .evidence(evidence)
                .recommendation(recommendation)
                .url(url)
                .triggered(true)
                .build();
    }

    private static boolean cacheIndicatesMiss(String cacheHeader) {
        if (cacheHeader == null || cacheHeader.isBlank()) {
            return false;
        }
        String lower = cacheHeader.toLowerCase(Locale.ROOT);
        return lower.contains("miss") || lower.equals("bypass");
    }

    private static boolean hasCompression(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return false;
        }
        String lower = encoding.toLowerCase(Locale.ROOT);
        return lower.contains("gzip") || lower.contains("br") || lower.contains("deflate");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? "none" : v;
    }

    private static String key(String method, String url) {
        return method + " " + url;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
