package com.rca.analyzer.client;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Graylog {@code callTrackingDetail.trackingByDB.mongo} hosts to Mongo Review {@code $hostname}.
 * Selects up to {@link #MAX_MONGO_HOSTS} hosts that contributed meaningful mongo time (p75 + floor).
 */
public final class GrafanaMongoContext {

    static final int MIN_MONGO_TIME_MS = 5;
    static final int MAX_MONGO_HOSTS = 5;
    static final double MONGO_TIME_PERCENTILE = 0.75;

    private GrafanaMongoContext() {
    }

    public static String toInfluxHostname(String host) {
        return GrafanaInfluxHostContext.toMongoHostPrefix(host);
    }

    /** Primary host for backward-compatible single-host callers. */
    public static String resolveFromLogs(List<Map<String, Object>> graylogLogs) {
        List<String> hosts = resolveHostsFromLogs(graylogLogs);
        return hosts.isEmpty() ? "" : hosts.get(0);
    }

    /**
     * Hosts to query in Mongo Review, ranked by mongo time contribution.
     * Only hosts with {@code timeTaken > 0} and at or above max({@link #MIN_MONGO_TIME_MS}, p75) are kept.
     */
    public static List<String> resolveHostsFromLogs(List<Map<String, Object>> graylogLogs) {
        if (graylogLogs == null || graylogLogs.isEmpty()) {
            return List.of();
        }
        Map<String, Long> hostTimes = new LinkedHashMap<>();
        for (Map<String, Object> log : graylogLogs) {
            collectFromLog(log, hostTimes);
        }
        return selectHosts(hostTimes);
    }

    static List<String> selectHosts(Map<String, Long> hostTimes) {
        if (hostTimes.isEmpty()) {
            return List.of();
        }
        List<Long> times = hostTimes.values().stream()
                .filter(t -> t > 0)
                .sorted()
                .toList();
        if (times.isEmpty()) {
            return List.of();
        }
        long threshold = Math.max(MIN_MONGO_TIME_MS, percentile(times, MONGO_TIME_PERCENTILE));
        return hostTimes.entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getValue() >= threshold)
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .limit(MAX_MONGO_HOSTS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void collectFromLog(Map<String, Object> log, Map<String, Long> hostTimes) {
        long logTime = longVal(log.get("mongoTimeTaken"));
        Object hostObj = log.get("mongoHost");
        if (hostObj != null && logTime > 0) {
            mergeHostTime(hostTimes, String.valueOf(hostObj), logTime);
        }
        Object detail = log.get("callTrackingDetail");
        if (detail instanceof Map<?, ?> callTrackingDetail) {
            collectFromCallTrackingDetail(callTrackingDetail, hostTimes);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectFromCallTrackingDetail(Map<?, ?> detail, Map<String, Long> hostTimes) {
        Object trackingByDb = detail.get("trackingByDB");
        if (!(trackingByDb instanceof Map<?, ?> tracking)) {
            return;
        }
        Object mongo = tracking.get("mongo");
        if (!(mongo instanceof Map<?, ?> mongoMap)) {
            return;
        }
        Object collectionDetails = mongoMap.get("collectionDetails");
        boolean collectedFromCollections = false;
        if (collectionDetails instanceof List<?> details) {
            for (Object item : details) {
                if (!(item instanceof Map<?, ?> collectionDetail)) {
                    continue;
                }
                String host = hostFromCollectionDetail(collectionDetail);
                long time = timeFromCollectionDetail(collectionDetail);
                if (!host.isBlank() && time > 0) {
                    mergeHostTime(hostTimes, host, time);
                    collectedFromCollections = true;
                }
            }
        }
        if (!collectedFromCollections) {
            long mongoTime = longVal(mongoMap.get("timeTaken"));
            if (mongoTime > 0) {
                String host = hostFromMongoNode(mongoMap);
                if (!host.isBlank()) {
                    mergeHostTime(hostTimes, host, mongoTime);
                }
            }
        }
    }

    private static String hostFromCollectionDetail(Map<?, ?> collectionDetail) {
        Object v1 = collectionDetail.get("v1");
        if (v1 instanceof Map<?, ?> v1Map) {
            Object host = v1Map.get("host");
            if (host != null && !String.valueOf(host).isBlank()) {
                return String.valueOf(host);
            }
        }
        return "";
    }

    private static long timeFromCollectionDetail(Map<?, ?> collectionDetail) {
        Object v2 = collectionDetail.get("v2");
        if (v2 instanceof Map<?, ?> v2Map) {
            return longVal(v2Map.get("timeTaken"));
        }
        return 0;
    }

    private static String hostFromMongoNode(Map<?, ?> mongoMap) {
        Object host = mongoMap.get("host");
        if (host != null && !String.valueOf(host).isBlank()) {
            return String.valueOf(host);
        }
        Object collectionDetails = mongoMap.get("collectionDetails");
        if (collectionDetails instanceof List<?> details && !details.isEmpty()
                && details.get(0) instanceof Map<?, ?> first) {
            return hostFromCollectionDetail(first);
        }
        return "";
    }

    private static void mergeHostTime(Map<String, Long> hostTimes, String host, long timeMs) {
        String influxHost = toInfluxHostname(host);
        if (influxHost.isBlank() || timeMs <= 0) {
            return;
        }
        hostTimes.merge(influxHost, timeMs, Math::max);
    }

    static long percentile(List<Long> sortedValues, double p) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double index = p * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = index - lower;
        return Math.round(sortedValues.get(lower) * (1.0 - fraction) + sortedValues.get(upper) * fraction);
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
