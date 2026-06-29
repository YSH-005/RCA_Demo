package com.rca.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Graylog-shaped logs from Kibana + HAR when Graylog API is unavailable.
 * Field shapes mirror production: access JSON message, callTrackingDetail.collectionDetails, perf-stats tree.
 */
final class SyntheticGraylogProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SyntheticGraylogProvider() {
    }

    static List<Map<String, Object>> build(
            String requestId,
            HarStitchContext har,
            List<Map<String, Object>> kibanaLogs) {

        KibanaSnapshot kibana = KibanaSnapshot.from(kibanaLogs);
        String pod = kibana.podName.isBlank() ? "webui-app-deployment-9c946c9cf-s6jhm" : kibana.podName;
        TimingScenario scenario = TimingScenario.from(requestId, har, kibana);

        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(ingressLog(requestId, har, scenario));
        logs.add(accessLog(requestId, pod, scenario));
        logs.add(callTrackingLog(requestId, pod, scenario));
        logs.addAll(perfStatsLogs(requestId, scenario));
        return logs;
    }

    private static Map<String, Object> ingressLog(String requestId, HarStitchContext har, TimingScenario scenario) {
        long requestMs = Math.max(Math.max(scenario.timeTakenMs(), har.waitMs()), 1L);
        long upstreamMs = Math.max((long) (requestMs * 0.92), scenario.timeTakenMs() - 20);

        Map<String, Object> entry = baseEntry("ingress", requestId, "nginx-ingress-webui-controller");
        entry.put("stream", "stdout");
        entry.put("source_type", "kubernetes_logs");
        entry.put("path", scenario.httpUri());
        entry.put("method", har.method().isBlank() ? "POST" : har.method());
        entry.put("status", scenario.status());
        entry.put("requestTimeMs", requestMs);
        entry.put("upstreamResponseTimeMs", upstreamMs);
        entry.put("upstreamConnectTimeMs", 0L);
        entry.put("durationMs", Math.max(har.durationMs(), requestMs));
        entry.put("vhost", "space-qa6.sprinklr.com");
        entry.put("message", "ingress request_id=%s path=%s request_time=%dms upstream=%dms"
                .formatted(requestId, scenario.httpUri(), requestMs, upstreamMs));
        return entry;
    }

    private static Map<String, Object> accessLog(String requestId, String pod, TimingScenario scenario) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "access");
        message.put("status", scenario.status());
        message.put("http_method", "POST");
        message.put("http_uri", scenario.httpUri());
        message.put("http_version", "HTTP/1.1");
        message.put("distributedTraceId", requestId);
        message.put("REQP-requestId", requestId);
        message.put("REQP-op", scenario.operationName());
        message.put("operationName", scenario.operationName());
        message.put("header_x-request-id", requestId);
        message.put("header_host", "space-qa6.sprinklr.com");
        message.put("header_x-op-name", scenario.operationName());
        message.put("GRAPHQL_OPERATION_MODULE", scenario.gqlModule());
        message.put("GRAPHQL_OPERATION_SUB_MODULE", scenario.gqlSubModule());
        message.put("GRAPHQL_OPERATION_CRITICALITY", "MEDIUM");
        message.put("sprAppType", "UI");
        message.put("timeSlab", scenario.slow() ? "truck" : "ferrari");
        message.put("mongo_timeTaken", scenario.mongoMs());
        message.put("mongo_totalCalls", scenario.mongoMs() > 0 ? 1 : 0);
        message.put("ch_timeTaken", scenario.chMs());
        message.put("kaf_totalCalls", scenario.chMs() > 0 ? 2 : 0);
        message.put("totalDBTime", scenario.totalDbMs());
        message.put("time_taken", scenario.timeTakenMs());
        message.put("api_call_time", scenario.timeTakenMs());
        message.put("cpu_time", 1);
        message.put("request_received_network_time_taken", scenario.networkMs());
        message.put("maxTimeTakenBySlowestStat", scenario.slowestStatMs());
        message.put("maxTimeTakenBy", scenario.slowestStat());
        message.put("dbCallStack", scenario.dbCallStack());
        message.put("perf-stats", scenario.perfStatsTree());
        message.put("perf-stats-inferred", scenario.perfStatsInferred());
        message.put("callTrackingDetail", toJson(scenario.callTrackingDetail()));

        Map<String, Object> entry = baseEntry("access", requestId, pod);
        entry.put("stream", "access");
        entry.put("source_type", "kubernetes_logs");
        entry.put("hostname", pod);
        entry.put("podName", pod);
        entry.put("message", toJson(message));
        entry.put("operationName", scenario.operationName());
        entry.put("httpUri", scenario.httpUri());
        entry.put("httpMethod", "POST");
        entry.put("status", scenario.status());
        entry.put("distributedTraceId", requestId);
        entry.put("dbCallStack", scenario.dbCallStack());
        entry.put("perfStatsTree", scenario.perfStatsTree());
        entry.put("perfStatsInferred", scenario.perfStatsInferred());
        entry.put("mongoTimeTaken", scenario.mongoMs());
        entry.put("chTimeTaken", scenario.chMs());
        entry.put("esTimeTaken", scenario.esMs());
        entry.put("dbTimeTaken", scenario.chMs());
        entry.put("totalDbTimeMs", scenario.totalDbMs());
        entry.put("apiTotalMs", scenario.timeTakenMs());
        entry.put("networkTimeMs", scenario.networkMs());
        entry.put("maxTimeTakenBySlowestStat", scenario.slowestStatMs());
        entry.put("maxTimeTakenBy", scenario.slowestStat());
        entry.put("callTrackingDetail", scenario.callTrackingDetail());
        entry.put("mongoHost", scenario.mongoHost());
        entry.put("esHost", scenario.esHost());
        return entry;
    }

    private static Map<String, Object> callTrackingLog(String requestId, String pod, TimingScenario scenario) {
        Map<String, Object> entry = baseEntry("calltracking", requestId, pod);
        entry.put("stream", "calltrackingbydb");
        entry.put("callTrackingDetail", scenario.callTrackingDetail());
        entry.put("podName", pod);
        entry.put("hostname", pod);
        entry.put("mongoTimeTaken", scenario.mongoMs());
        entry.put("esTimeTaken", scenario.esMs());
        entry.put("dbTimeTaken", scenario.chMs());
        entry.put("apiTotalMs", scenario.timeTakenMs());
        entry.put("totalDbTimeMs", scenario.totalDbMs());
        entry.put("mongoHost", scenario.mongoHost());
        entry.put("esHost", scenario.esHost());
        entry.put("message", "callTrackingDetail mongo=%dms ch=%dms es=%dms totalDB=%dms api=%dms"
                .formatted(scenario.mongoMs(), scenario.chMs(), scenario.esMs(),
                        scenario.totalDbMs(), scenario.timeTakenMs()));
        return entry;
    }

    private static List<Map<String, Object>> perfStatsLogs(String requestId, TimingScenario scenario) {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (PerfLeaf leaf : scenario.perfLeaves()) {
            Map<String, Object> entry = baseEntry("perfstats", requestId, "");
            entry.put("stream", "perfstats");
            entry.put("statName", leaf.statName());
            entry.put("fromParent", leaf.fromParent());
            entry.put("fromRoot", leaf.fromRoot());
            entry.put("stopTimeMs", leaf.stopTimeMs());
            entry.put("timeTakenMs", leaf.timeTakenMs());
            entry.put("message", "perfstats stat=%s timeTaken=%dms stopTime=%dms".formatted(
                    leaf.statName(), leaf.timeTakenMs(), leaf.stopTimeMs()));
            stats.add(entry);
        }
        return stats;
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

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private record TimingScenario(
            String operationName,
            String httpUri,
            String gqlModule,
            String gqlSubModule,
            int status,
            long timeTakenMs,
            long mongoMs,
            long chMs,
            long esMs,
            long totalDbMs,
            long networkMs,
            String mongoHost,
            String mongoCollection,
            String esHost,
            String slowestStat,
            long slowestStatMs,
            String dbCallStack,
            String perfStatsTree,
            String perfStatsInferred,
            Map<String, Object> callTrackingDetail,
            List<PerfLeaf> perfLeaves) {

        boolean slow() {
            return timeTakenMs >= 500;
        }

        static TimingScenario from(String requestId, HarStitchContext har, KibanaSnapshot kibana) {
            String operation = operationName(har);
            String uri = httpUri(har, operation);
            long apiTotal = Math.max(Math.max(har.durationMs(), kibana.maxExecMs + kibana.maxWaitMs), 2);
            long esMs = Math.max(kibana.maxEsMs, 0);
            long mongoMs;
            long chMs = 0;
            String mongoHost = "qa6-mongo-core-40.sprinklr.com";
            String mongoCollection = mongoCollection(operation);
            String esHost = kibana.esHost.isBlank() ? "es-data-qa6-case1-es-1-b" : kibana.esHost;

            if (esMs >= 500) {
                mongoMs = Math.max(esMs / 5, 20);
            } else if (apiTotal > 800 && esMs == 0) {
                chMs = Math.max((long) (apiTotal * 0.92), 800);
                mongoMs = Math.min(Math.max(apiTotal / 50, 1), 30);
            } else if (apiTotal <= 10) {
                mongoMs = 1;
            } else {
                mongoMs = Math.max(apiTotal / 8, 5);
            }

            long totalDb = mongoMs + chMs + esMs;
            if (totalDb <= 0) {
                totalDb = mongoMs;
            }
            long networkMs = kibana.maxNetworkMs > 0 ? kibana.maxNetworkMs : Math.min(200, apiTotal / 10);

            String slowestStat;
            long slowestStatMs;
            if (chMs > mongoMs && chMs > esMs) {
                slowestStat = "clickhouse.query";
                slowestStatMs = chMs;
            } else if (esMs > mongoMs) {
                slowestStat = "ESQueryExecution";
                slowestStatMs = esMs;
            } else {
                slowestStat = "mongo." + mongoCollection + ".find";
                slowestStatMs = mongoMs;
            }

            String dbCallStack = dbCallStack(operation, mongoCollection, slowestStatMs);
            boolean slow = apiTotal >= 500;
            String perfTree = perfStatsTree(operation, uri, apiTotal, mongoCollection, mongoMs, chMs, esMs, slow);
            String perfInferred = perfStatsInferred(perfTree, slow);
            Map<String, Object> callTracking = callTrackingDetail(
                    mongoHost, mongoCollection, mongoMs, esHost, esMs, chMs);
            List<PerfLeaf> leaves = perfLeaves(operation, uri, apiTotal, mongoCollection, mongoMs, chMs, esMs);

            return new TimingScenario(
                    operation, uri, gqlModule(operation), gqlSubModule(operation), 200,
                    apiTotal, mongoMs, chMs, esMs, totalDb, networkMs,
                    mongoHost, mongoCollection, esHost, slowestStat, slowestStatMs,
                    dbCallStack, perfTree, perfInferred, callTracking, leaves);
        }

        private static String operationName(HarStitchContext har) {
            if (!har.apiName().isBlank()) {
                return har.apiName();
            }
            if (har.url().contains("universalCases")) {
                return "universalCases";
            }
            if (har.url().contains("caseStreamFeed")) {
                return "caseStreamFeed";
            }
            return "getUserInboxNotifications";
        }

        private static String httpUri(HarStitchContext har, String operation) {
            if (!har.url().isBlank() && har.url().contains("/ui/")) {
                try {
                    return java.net.URI.create(har.url()).getPath();
                } catch (Exception ignored) {
                    return har.url();
                }
            }
            return switch (operation) {
                case "universalCases" -> "/ui/graphql/care/universalCases";
                case "caseStreamFeed" -> "/ui/graphql/care/caseStreamFeed";
                case "paginatedAssociatedMessagesForCase" -> "/ui/graphql/care/paginatedAssociatedMessagesForCase";
                default -> "/ui/graphql/reporting/" + operation;
            };
        }

        private static String gqlModule(String operation) {
            return switch (operation) {
                case "universalCases", "caseStreamFeed", "paginatedAssociatedMessagesForCase" -> "CARE";
                default -> "CARE";
            };
        }

        private static String gqlSubModule(String operation) {
            return operation.contains("Inbox") || operation.contains("Notification") ? "LIVE_CHAT" : "REPORTING";
        }

        private static String mongoCollection(String operation) {
            return operation.contains("Inbox") || operation.contains("Notification")
                    ? "livechat_notificationRecord"
                    : "caseRecord";
        }

        private static Map<String, Object> callTrackingDetail(
                String mongoHost, String collection, long mongoMs, String esHost, long esMs, long chMs) {
            Map<String, Object> trackingByDb = new LinkedHashMap<>();

            if (mongoMs > 0) {
                Map<String, Object> v1 = new LinkedHashMap<>();
                v1.put("async", false);
                v1.put("collection", collection);
                v1.put("dbName", "PARTNER66000000_SYSTEM");
                v1.put("host", mongoHost);
                v1.put("operationName", "find");
                v1.put("partnerId", "66000000");
                v1.put("secondary", false);
                v1.put("serverType", "SYSTEM");

                Map<String, Object> v2 = Map.of("count", 1, "timeTaken", mongoMs);
                Map<String, Object> collectionDetail = Map.of("v1", v1, "v2", v2);

                Map<String, Object> mongo = new LinkedHashMap<>();
                mongo.put("collectionDetails", List.of(collectionDetail));
                mongo.put("serviceType", "mongo");
                mongo.put("timeTaken", mongoMs);
                trackingByDb.put("mongo", mongo);
            }

            if (esMs > 0) {
                trackingByDb.put("es", Map.of(
                        "host", esHost,
                        "timeTaken", esMs,
                        "count", 2,
                        "serviceType", "es"));
            }

            if (chMs > 0) {
                trackingByDb.put("clickhouse", Map.of(
                        "host", "qa6-clickhouse-analytics-1",
                        "timeTaken", chMs,
                        "count", 1,
                        "serviceType", "clickhouse"));
            }

            return Map.of("trackingByDB", trackingByDb);
        }

        private static String dbCallStack(String operation, String collection, long mongoMs) {
            String leaf = operation.contains("Inbox") || operation.contains("Notification")
                    ? "com.spr.chat.services.RemoteChatOps.paginatedQueryById(RemoteChatOps.java:154){mongo.SYSTEM."
                    + collection + ".find=" + mongoMs + "}"
                    : "com.spr.care.services.CaseService.fetchCases(CaseService.java:210){mongo.SYSTEM."
                    + collection + ".find=" + mongoMs + "}";
            return """
                    com.spr.filters.DebugFilter.doFilter(DebugFilter.java:47)
                     com.spr.filters.JWTUIAuthFilter.doFilter(JWTUIAuthFilter.java:106)
                      com.spr.servlet.SprGraphQLServlet.doPost(SprGraphQLServlet.java:117)
                       com.spr.graphql.execution.BulkHeadExecutionStrategy.execute(BulkHeadExecutionStrategy.java:81)
                        com.spr.graphql.api.care.LiveChatGraphqlAPI.%s(LiveChatGraphqlAPI.java:245)
                         %s""".formatted(operation, leaf);
        }

        private static String perfStatsTree(
                String operation, String uri, long apiTotal, String collection,
                long mongoMs, long chMs, long esMs, boolean slow) {
            String outMissed = slow ? " outMissed" : "";
            String gqlPath = uri.substring(uri.lastIndexOf('/') + 1);
            long root = apiTotal;
            long gql = Math.max(root - 1, 1);
            long execute = Math.max(gql - 1, 1);
            long fieldNames = Math.max(execute / 2, 1);
            long storeLeaf = Math.max(mongoMs, Math.max(chMs, esMs));
            String storeStat = chMs > 0 ? "clickhouse.query" : (esMs > mongoMs ? "ESQueryExecution" : "mongo." + collection + ".find");

            return """
                    Depth: StatName(StatCount) FromParent,FromRoot,StopTime: TimeTaken
                    0: doFilter.statTotalTime(1) : 0, 0, %d : %d
                      1: GRAPHQL_POST : %s(1) : 0, 0, %d : %d
                        2: GQL-(1) : 0, 0, %d : %d%s
                          3: graphql.executeAsync(1) : 0, 0, %d : %d
                            4: parseValidateAndExecute(1) : 0, 0, %d : %d
                              5: graphql.execute(1) : 0, 0, %d : %d
                                6: execute.fieldNames(1) : 0, 0, %d : %d
                                  7: %s(1) : 1, 2, 0 : %d
                    """.formatted(root, root, gqlPath, gql, gql, gql, gql, outMissed,
                    gql, gql, gql, gql, gql, gql, fieldNames, fieldNames, storeStat, storeLeaf).stripTrailing();
        }

        private static String perfStatsInferred(String perfTree, boolean slow) {
            if (!slow) {
                return perfTree.replace("TimeTaken\n", "TimeTaken: Reason\n")
                        .lines()
                        .map(line -> line.contains(":") && !line.contains("Reason")
                                ? line + " : ok" : line)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
            }
            return perfTree.replace("TimeTaken\n", "TimeTaken: Reason\n")
                    .lines()
                    .map(line -> {
                        if (!line.contains("TimeTaken") || line.contains("Reason")) {
                            return line;
                        }
                        return line + " : slow";
                    })
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }

        private static List<PerfLeaf> perfLeaves(
                String operation, String uri, long apiTotal, String collection,
                long mongoMs, long chMs, long esMs) {
            String root = operation;
            String gqlPath = uri.substring(uri.lastIndexOf('/') + 1);
            List<PerfLeaf> leaves = new ArrayList<>();
            leaves.add(new PerfLeaf("doFilter.statTotalTime", "", "", apiTotal, apiTotal));
            leaves.add(new PerfLeaf("GRAPHQL_POST : " + gqlPath, "", root, apiTotal, apiTotal));
            leaves.add(new PerfLeaf("GQL-(" + operation + ")", root, root, apiTotal, apiTotal));
            if (chMs > 0) {
                leaves.add(new PerfLeaf("clickhouse.query", root, root, chMs, chMs));
            } else if (esMs > 0) {
                leaves.add(new PerfLeaf("ESQueryExecution", root, root, esMs, esMs));
            }
            if (mongoMs > 0) {
                leaves.add(new PerfLeaf("mongo." + collection + ".find", root, root, mongoMs, mongoMs));
            }
            return leaves;
        }
    }

    private record PerfLeaf(String statName, String fromParent, String fromRoot, long stopTimeMs, long timeTakenMs) {
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
