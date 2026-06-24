package com.rca.analyzer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Graylog-shaped log maps from Kibana + HAR when the Graylog API is unavailable.
 * Field shapes match expected production logs: ingress, app, callTrackingDetail, perfstats.
 */
final class SyntheticGraylogProvider {

    private SyntheticGraylogProvider() {
    }

    static List<Map<String, Object>> build(
            String requestId,
            HarStitchContext har,
            List<Map<String, Object>> kibanaLogs) {

        KibanaSnapshot kibana = KibanaSnapshot.from(kibanaLogs);
        String pod = kibana.podName.isBlank() ? "webui-app-deployment-unknown" : kibana.podName;
        String path = pathFromHar(har);
        long apiTotalMs = Math.max(Math.max(har.durationMs(), kibana.maxExecMs + kibana.maxWaitMs), 1L);

        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(ingressLog(requestId, har, path));
        logs.add(appLog(requestId, pod, "com.spr.contact.DistributedContactServiceImpl",
                "Unable to find distributed user config for the user - 66077374"));
        logs.add(callTrackingLog(requestId, pod, kibana, apiTotalMs));
        logs.addAll(perfStatsLogs(requestId, har, kibana, apiTotalMs));
        return logs;
    }

    private static Map<String, Object> ingressLog(String requestId, HarStitchContext har, String path) {
        double requestTimeSec = Math.max(har.waitMs(), 1) / 1000.0;
        double upstreamSec = requestTimeSec * 0.92;
        double durationSec = har.durationMs() > 0 ? har.durationMs() / 1000.0 : requestTimeSec;

        Map<String, Object> entry = baseEntry("ingress", requestId, "nginx-ingress-webui-controller");
        entry.put("stream", "stdout");
        entry.put("source_type", "kubernetes_logs");
        entry.put("path", path);
        entry.put("method", har.method().isBlank() ? "POST" : har.method());
        entry.put("status", 200);
        entry.put("requestTimeMs", Math.round(requestTimeSec * 1000));
        entry.put("upstreamResponseTimeMs", Math.round(upstreamSec * 1000));
        entry.put("upstreamConnectTimeMs", 0L);
        entry.put("durationMs", Math.round(durationSec * 1000));
        entry.put("vhost", "space-qa6.sprinklr.com");
        entry.put("message", "ingress request_id=%s path=%s request_time=%.3fs upstream=%.3fs"
                .formatted(requestId, path, requestTimeSec, upstreamSec));
        return entry;
    }

    private static Map<String, Object> appLog(String requestId, String pod, String logger, String message) {
        Map<String, Object> entry = baseEntry("app", requestId, pod);
        entry.put("stream", "stdout");
        entry.put("source_type", "kubernetes_logs");
        entry.put("distributedTraceId", requestId);
        entry.put("hostname", pod);
        entry.put("logger", logger);
        entry.put("message", message);
        entry.put("podName", pod);
        return entry;
    }

    private static Map<String, Object> callTrackingLog(
            String requestId, String pod, KibanaSnapshot kibana, long apiTotalMs) {

        long esMs = Math.max(kibana.maxEsMs, 0);
        long mongoMs = esMs > 0 ? Math.max(esMs / 4, 40) : 0;
        long execMs = Math.max(kibana.maxExecMs, 0);
        long waitMs = Math.max(kibana.maxWaitMs, 0);
        long dbMs = execMs > 0 ? execMs : Math.max(mongoMs / 2, 0);
        long storeMs = esMs + mongoMs + dbMs;
        long apiTotal = Math.max(Math.max(Math.max(apiTotalMs, waitMs + execMs + esMs), storeMs + 100L), 1L);
        String esHost = kibana.esHost.isBlank() ? "es-data-qa6-case1-es-1-b" : kibana.esHost;
        String mongoHost = "mongo-qa6-shard-1";

        Map<String, Object> trackingByDb = new LinkedHashMap<>();
        if (esMs > 0) {
            trackingByDb.put("es", storeEntry(esHost, esMs, 3));
        }
        if (mongoMs > 0) {
            trackingByDb.put("mongo", storeEntry(mongoHost, mongoMs, 2));
        }
        if (dbMs > 0) {
            trackingByDb.put("db", storeEntry(pod, dbMs, 1));
        }

        Map<String, Object> objectReq = storeEntry(esHost, Math.max(esMs / 3, 10), 1);
        Map<String, Object> microserviceReq = storeEntry(pod, Math.max(kibana.maxExecMs, 100), 2);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("trackingByDB", trackingByDb);
        detail.put("objectReq", objectReq);
        detail.put("microserviceReq", microserviceReq);
        detail.put("estimateTakenMs", waitMs > 0 ? waitMs : storeMs);

        Map<String, Object> entry = baseEntry("calltracking", requestId, pod);
        entry.put("stream", "calltrackingbydb");
        entry.put("callTrackingDetail", detail);
        entry.put("podName", pod);
        entry.put("esTimeTaken", esMs);
        entry.put("mongoTimeTaken", mongoMs);
        entry.put("dbTimeTaken", dbMs);
        entry.put("apiTotalMs", apiTotal);
        entry.put("esHost", esHost);
        entry.put("mongoHost", mongoHost);
        entry.put("estimateTakenMs", detail.get("estimateTakenMs"));
        entry.put("message", "callTrackingDetail es=%dms mongo=%dms db=%dms wait=%dms apiTotal=%dms host=%s"
                .formatted(esMs, mongoMs, dbMs, waitMs, apiTotal, esHost));
        return entry;
    }

    private static List<Map<String, Object>> perfStatsLogs(
            String requestId, HarStitchContext har, KibanaSnapshot kibana, long apiTotalMs) {

        String root = har.apiName().isBlank() ? "apiRequest" : har.apiName();
        long exec = Math.max(kibana.maxExecMs, apiTotalMs / 3);

        List<Map<String, Object>> stats = new ArrayList<>();
        stats.add(perfStat(requestId, root, "", root, exec, exec));
        long esLeaf = Math.max(kibana.maxEsMs, exec / 3);
        stats.add(perfStat(requestId, "ESQueryExecution", root, root, esLeaf, esLeaf));
        long mongoLeaf = Math.max(esLeaf / 2, 40);
        stats.add(perfStat(requestId, "MongoFetch", root, root, mongoLeaf, mongoLeaf));
        long msLeaf = Math.max(exec - esLeaf - mongoLeaf, 50);
        stats.add(perfStat(requestId, "MicroserviceCall", root, root, msLeaf, msLeaf));
        return stats;
    }

    private static Map<String, Object> perfStat(
            String requestId, String statName, String fromParent, String fromRoot,
            long stopTimeMs, long timeTakenMs) {

        Map<String, Object> entry = baseEntry("perfstats", requestId, "");
        entry.put("stream", "perfstats");
        entry.put("statName", statName);
        entry.put("fromParent", fromParent);
        entry.put("fromRoot", fromRoot.isBlank() ? statName : fromRoot);
        entry.put("stopTimeMs", stopTimeMs);
        entry.put("timeTakenMs", timeTakenMs);
        entry.put("message", "perfstats stat=%s timeTaken=%dms stopTime=%dms".formatted(
                statName, timeTakenMs, stopTimeMs));
        return entry;
    }

    private static Map<String, Object> storeEntry(String host, long timeTakenMs, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", host);
        m.put("timeTakenMs", timeTakenMs);
        m.put("count", count);
        return m;
    }

    private static Map<String, Object> baseEntry(String logKind, String requestId, String hostname) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", "stub");
        entry.put("logKind", logKind);
        entry.put("requestId", requestId);
        if (hostname != null && !hostname.isBlank()) {
            entry.put("hostname", hostname);
        }
        return entry;
    }

    private static String pathFromHar(HarStitchContext har) {
        if (har.url().contains("/universalCases")) {
            return "/ui/graphql/care/universalCases";
        }
        if (har.url().contains("caseStreamFeed")) {
            return "/ui/graphql/care/caseStreamFeed";
        }
        if (har.url().contains("paginatedAssociatedMessagesForCase")) {
            return "/ui/graphql/care/paginatedAssociatedMessagesForCase";
        }
        if (har.url().contains("/feed")) {
            return "/feed";
        }
        return har.url().isBlank() ? "/ui/graphql/care/universalCases" : har.url();
    }

    private record KibanaSnapshot(
            String podName,
            String esHost,
            long maxEsMs,
            long maxExecMs,
            long maxWaitMs,
            long maxNetworkMs) {

        static KibanaSnapshot from(List<Map<String, Object>> logs) {
            if (logs == null || logs.isEmpty()) {
                return new KibanaSnapshot("", "", 0, 0, 0, 0);
            }
            String pod = "";
            String esHost = "";
            long maxEs = 0, maxExec = 0, maxWait = 0, maxNet = 0;
            for (Map<String, Object> log : logs) {
                pod = firstNonBlank(pod, str(log.get("podName")));
                esHost = firstNonBlank(esHost, str(log.get("esHost")));
                maxEs = Math.max(maxEs, longVal(log.get("esTimeMs")));
                maxExec = Math.max(maxExec, longVal(log.get("totalExecTimeMs")));
                maxWait = Math.max(maxWait, longVal(log.get("totalWaitTimeMs")));
                maxNet = Math.max(maxNet, longVal(log.get("networkTimeMs")));
            }
            return new KibanaSnapshot(pod, esHost, maxEs, maxExec, maxWait, maxNet);
        }

        private static String firstNonBlank(String a, String b) {
            return a != null && !a.isBlank() ? a : (b != null ? b : "");
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }

        private static long longVal(Object o) {
            if (o instanceof Number n) {
                return n.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
