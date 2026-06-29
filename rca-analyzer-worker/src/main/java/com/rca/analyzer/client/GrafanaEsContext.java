package com.rca.analyzer.client;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Kibana {@code attributes.esHost} and Graylog calltracking ES hosts to Elasticsearch-v7
 * {@code $hostname}. Selects up to {@link #MAX_ES_HOSTS} hosts with meaningful ES time (p75 + floor).
 */
public final class GrafanaEsContext {

    static final int MIN_ES_TIME_MS = 5;
    static final int MAX_ES_HOSTS = 5;
    static final double ES_TIME_PERCENTILE = 0.75;

    private GrafanaEsContext() {
    }

    public static String toInfluxHostname(String host) {
        return GrafanaInfluxHostContext.toEsHostPrefix(host);
    }

    /** Primary host for backward-compatible single-host callers. */
    public static String resolveFromLogs(List<Map<String, Object>> kibanaLogs, List<Map<String, Object>> graylogLogs) {
        List<String> hosts = resolveHostsFromLogs(kibanaLogs, graylogLogs);
        return hosts.isEmpty() ? "" : hosts.get(0);
    }

    /**
     * ES data-node hosts to query in Elasticsearch-v7, ranked by ES time contribution.
     * Only hosts with time &gt; 0 and at or above max({@link #MIN_ES_TIME_MS}, p75) are kept.
     */
    public static List<String> resolveHostsFromLogs(
            List<Map<String, Object>> kibanaLogs, List<Map<String, Object>> graylogLogs) {
        Map<String, Long> hostTimes = new LinkedHashMap<>();
        if (kibanaLogs != null) {
            for (Map<String, Object> log : kibanaLogs) {
                collectFromKibanaLog(log, hostTimes);
            }
        }
        if (graylogLogs != null) {
            for (Map<String, Object> log : graylogLogs) {
                collectFromGraylogLog(log, hostTimes);
            }
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
        long threshold = Math.max(MIN_ES_TIME_MS, percentile(times, ES_TIME_PERCENTILE));
        return hostTimes.entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getValue() >= threshold)
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .limit(MAX_ES_HOSTS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void collectFromKibanaLog(Map<String, Object> log, Map<String, Long> hostTimes) {
        long esTime = longVal(log.get("esTimeMs"));
        Object hostObj = log.get("esHost");
        if (hostObj != null && esTime > 0) {
            mergeHostTime(hostTimes, String.valueOf(hostObj), esTime);
        }
    }

    private static void collectFromGraylogLog(Map<String, Object> log, Map<String, Long> hostTimes) {
        long logTime = longVal(log.get("esTimeTaken"));
        Object hostObj = log.get("esHost");
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
        Object es = tracking.get("es");
        if (!(es instanceof Map<?, ?> esMap)) {
            return;
        }
        long esTime = longVal(esMap.get("timeTaken"));
        if (esTime > 0) {
            Object host = esMap.get("host");
            if (host != null && !String.valueOf(host).isBlank()) {
                mergeHostTime(hostTimes, String.valueOf(host), esTime);
            }
        }
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
