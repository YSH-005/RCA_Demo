package com.rca.analyzer.config;

import com.rca.common.observability.SessionCookieNormalizer;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /**
     * When false (default), never use synthetic Graylog/Grafana — only live API data contributes to scoring.
     * When true, allow stub providers and synthetic fallbacks on auth failure or missing credentials.
     */
    private boolean useSynthetic = false;

    private Graylog graylog = new Graylog();
    private Kibana kibana = new Kibana();
    private Grafana grafana = new Grafana();

    @Data
    public static class Graylog {
        private String url = "";
        private String token = "";
        /** Temporary SSO workaround — wrap entire value in single quotes in .env. */
        private String sessionCookie = "";
        /** Use stub when true or when no token/session cookie is configured. */
        private boolean stub = true;
        private int maxResults = 50;
    }

    @Data
    public static class Kibana {
        private String url = "";
        private String apiKey = "";
        private String indexPattern = "monitoring*";
        private String errorIndexPattern = "";
        private int maxResults = 50;
    }

    @Data
    public static class Grafana {
        private String url = "";
        private String apiKey = "";
        /** Optional Grafana session cookie value (e.g. grafana_session=...; user.env.type=ENTERPRISE). */
        private String sessionCookie = "";
        /** Use stub when true or when api-key is blank. */
        private boolean stub = true;
        private String datasourceType = "prometheus";
        /** Default Prometheus datasource UID (Kubernetes-Pod-Stats-V2 / k8s cluster). */
        private String datasourceUid = "";
        /**
         * Named datasource UIDs — one per infra dashboard category.
         * Keys: k8s, elasticsearch, mongo, ingress (match {@link PrometheusMetricQuery#datasource}).
         */
        private Map<String, String> datasources = new LinkedHashMap<>();
        /** Grafana org id header (x-grafana-org-id). */
        private int orgId = 1;
        /** POST path for Prometheus datasource query API. */
        private String queryPath = "/api/ds/query?ds_type=prometheus";
        /** POST path for InfluxDB datasource query API (Mongo Review dashboard). */
        private String influxQueryPath = "/api/ds/query?ds_type=influxdb";
        private int intervalMs = 15000;
        private int maxDataPoints = 336;
        /** Default k8s namespace for pod-stats dashboard (Grafana variable {@code $namespace}). */
        private String k8sNamespace = "spr-apps";
        /** Ingress Review — {@code $namespace} on nginx controller metrics. */
        private String ingressNamespace = "ingress-nginx-external";
        /** Ingress Review — {@code $controller_class}. */
        private String ingressControllerClass = "k8s.io/nginx-external";
        /** Maps controller namespace → logical datasource key (e.g. ingress-nginx-internal → ingress-selenium). */
        private Map<String, String> ingressDatasourceByNamespace = new LinkedHashMap<>();
        /** Maps k8s node name substring → logical datasource key (e.g. selenium-gke → ingress-selenium). */
        private Map<String, String> ingressDatasourceByNodePattern = new LinkedHashMap<>();
        /** @deprecated use prometheus-metrics; kept for backward compatibility */
        private String fluxQueryTemplate = "";
        /** Named Prometheus expressions — placeholders: {podName}, {esHost}, {mongoHost} */
        private Map<String, PrometheusMetricQuery> prometheusMetrics = new LinkedHashMap<>();
        /** Mongo Review InfluxQL — placeholders: {mongoHost}, {esHost} (Influx {@code $hostname}). */
        private Map<String, InfluxMetricQuery> influxMetrics = new LinkedHashMap<>();

        public Map<String, PrometheusMetricQuery> resolvedMetrics() {
            if (prometheusMetrics != null && !prometheusMetrics.isEmpty()) {
                return prometheusMetrics;
            }
            return defaultPrometheusMetrics();
        }

        private static Map<String, PrometheusMetricQuery> defaultPrometheusMetrics() {
            Map<String, PrometheusMetricQuery> metrics = new LinkedHashMap<>();
            metrics.put("container_cpu_usage_rate", query("k8s",
                    """
                    sum(rate(container_cpu_usage_seconds_total{container!="linkerd-proxy",image!="",pod=~"^{podName}"}[5m])) by (pod)
                    """));
            metrics.put("container_memory_working_set_bytes", query("k8s",
                    """
                    sum(container_memory_working_set_bytes{container!="linkerd-proxy",image!="",pod=~"^{podName}"})
                    """));
            metrics.put("container_memory_usage_rate", query("k8s",
                    """
                    sum(container_memory_working_set_bytes{container!="linkerd-proxy",image!="",pod=~"^{podName}"})
                    /
                    sum(kube_pod_container_resource_limits{resource="memory",pod=~"^{podName}",job!="discovery",release="prometheus"})
                    """));
            metrics.put("jvm_threads_current", query("k8s",
                    """
                    jvm_threads_current{kubernetes_pod_name=~"^{podName}"}
                    """));
            metrics.put("thread_queue", query("k8s",
                    """
                    jvm_threads_current{kubernetes_pod_name=~"^{podName}"}
                    """));
            metrics.put("gc_old_gen_ms", query("k8s",
                    """
                    (
                      rate(jvm_gc_collection_seconds_sum{gc="G1 Old Generation",kubernetes_pod_name=~"^{podName}"}[5m])
                      /
                      rate(jvm_gc_collection_seconds_count{gc="G1 Old Generation",kubernetes_pod_name=~"^{podName}"}[5m])
                    )
                    or
                    increase(jvm_gc_collection_seconds_sum{gc="ZGC Major Cycles",kubernetes_pod_name=~"^{podName}"}[5m])
                    """));
            metrics.put("container_cpu_throttle_pct", query("k8s",
                    """
                    sum(increase(container_cpu_cfs_throttled_periods_total{image!="",pod=~"^{podName}"}[5m]))
                    / sum(increase(container_cpu_cfs_periods_total{image!="",pod=~"^{podName}"}[5m])) * 100
                    """));
            metrics.put("pod_restarts_total", query("k8s",
                    """
                    sum(increase(kube_pod_container_status_restarts_total{namespace=~"{namespace}",pod=~"^{podName}"}[15m]))
                    """));
            metrics.put("probe_liveness_failed", query("k8s",
                    """
                    sum(increase(prober_probe_total{probe_type="Liveness",result="failed",pod=~"^{podName}"}[5m]))
                    """));
            metrics.put("probe_readiness_failed", query("k8s",
                    """
                    sum(increase(prober_probe_total{probe_type="Readiness",result="failed",pod=~"^{podName}"}[5m]))
                    """));
            metrics.put("pod_terminated_reason", query("k8s",
                    """
                    sum(kube_pod_container_status_last_terminated_reason{namespace=~"{namespace}",pod=~"^{podName}"})
                    """));
            metrics.put("pod_age_seconds", query("k8s",
                    """
                    time() - kube_pod_start_time{pod=~"^{podName}"}
                    """));
            metrics.put("ingress_5xx_per_min", query("ingress",
                    """
                    sum(increase(nginx_ingress_controller_requests{controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}",status=~"5.*"}[2m]))
                    """));
            metrics.put("ingress_4xx_per_min", query("ingress",
                    """
                    sum(increase(nginx_ingress_controller_requests{controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}",status=~"4.*"}[2m]))
                    """));
            metrics.put("ingress_p95_latency_seconds", query("ingress",
                    """
                    histogram_quantile(0.95, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress!="",controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}"}[5m])) by (le))
                    """));
            metrics.put("ingress_p99_latency_seconds", query("ingress",
                    """
                    histogram_quantile(0.99, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress!="",controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}"}[5m])) by (le))
                    """));
            metrics.put("ingress_success_rate", query("ingress",
                    """
                    sum(rate(nginx_ingress_controller_requests{controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}",status!~"[4-5].*"}[2m]))
                    / sum(rate(nginx_ingress_controller_requests{controller_pod=~"^{controllerPod}",controller_class=~"{controllerClass}",controller_namespace=~"{ingressNamespace}",ingress=~"{ingressName}"}[2m]))
                    """));
            return metrics;
        }

        private static PrometheusMetricQuery query(String datasource, String expr) {
            PrometheusMetricQuery q = new PrometheusMetricQuery();
            q.setDatasource(datasource);
            q.setExpr(expr.trim());
            return q;
        }

        public Map<String, InfluxMetricQuery> resolvedInfluxMetrics() {
            if (influxMetrics != null && !influxMetrics.isEmpty()) {
                return influxMetrics;
            }
            return defaultInfluxMetrics();
        }

        private static Map<String, InfluxMetricQuery> defaultInfluxMetrics() {
            Map<String, InfluxMetricQuery> metrics = new LinkedHashMap<>();
            metrics.put("mongo_scanned_objects", influxQuery("mongo", "mongo",
                    """
                    SELECT value FROM /^.*QueryExecutor_scanned_objects/ WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_scanned", influxQuery("mongo", "mongo",
                    """
                    SELECT value FROM /^.*QueryExecutor_scanned/ WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_connections_current", influxQuery("mongo", "mongo",
                    """
                    SELECT "value" FROM /^.*Connections_current/ WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_down", influxQuery("mongo", "mongo",
                    """
                    SELECT value FROM /^.*MongoDown_mongo_down/ WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_cpu_total", influxQuery("mongo", "mongo",
                    """
                    SELECT "value" FROM "cpu_total" WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_cpu_iowait", influxQuery("mongo", "mongo",
                    """
                    SELECT "value" FROM "avg-cpu_pct_iowait" WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("mongo_load_avg", influxQuery("mongo", "mongo",
                    """
                    SELECT "value" FROM "load_avg_one" WHERE "host" =~ /^{mongoHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_query_latency_ms", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-search_indices_index_query_latency" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_threadpool_search_rejected", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-threadpool_thread_pool_search_rejected" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_threadpool_search_queue", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-threadpool_thread_pool_search_queue" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_threadpool_search_active", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-threadpool_thread_pool_search_active" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_threadpool_write_rejected", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-threadpool_thread_pool_write_rejected" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_breakers_request_tripped", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-breakers_request_tripped" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_breakers_fielddata_tripped", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-breakers_fielddata_tripped" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            metrics.put("es_cluster_health_status", influxQuery("elasticsearch", "es",
                    """
                    SELECT "value" FROM "es-cluster_health_status" WHERE "host" =~ /^{esHost}/ AND $timeFilter GROUP BY "host"
                    """));
            return metrics;
        }

        private static InfluxMetricQuery influxQuery(String datasource, String hostKey, String query) {
            InfluxMetricQuery q = new InfluxMetricQuery();
            q.setDatasource(datasource);
            q.setHostKey(hostKey);
            q.setQuery(query.trim());
            return q;
        }

        /** Resolve Prometheus datasource UID for a metric (named datasource → map → global default). */
        public String resolveDatasourceUid(PrometheusMetricQuery query) {
            return resolveDatasourceUid(
                    query != null ? query.getDatasourceUid() : "",
                    query != null ? query.getDatasource() : "");
        }

        /** Resolve ingress Prometheus UID using controller namespace / node from incident logs. */
        public String resolveIngressDatasourceUid(
                com.rca.analyzer.client.GrafanaQueryContext context,
                PrometheusMetricQuery query) {
            if (query != null && query.getDatasourceUid() != null && !query.getDatasourceUid().isBlank()) {
                return query.getDatasourceUid().trim();
            }
            return resolveDatasourceUid("", resolveIngressDatasourceKey(context));
        }

        public String resolveIngressDatasourceKey(com.rca.analyzer.client.GrafanaQueryContext context) {
            if (context != null) {
                String node = context.k8sNode() != null ? context.k8sNode().toLowerCase() : "";
                for (Map.Entry<String, String> entry : ingressDatasourceByNodePattern.entrySet()) {
                    if (!node.isBlank() && node.contains(entry.getKey().toLowerCase())) {
                        return entry.getValue();
                    }
                }
                String ns = context.ingressNamespace() != null ? context.ingressNamespace() : "";
                String mapped = ingressDatasourceByNamespace.get(ns);
                if (mapped != null && !mapped.isBlank()) {
                    return mapped;
                }
            }
            return "ingress";
        }

        /** Resolve InfluxDB datasource UID for a metric (named datasource → map → global default). */
        public String resolveInfluxDatasourceUid(InfluxMetricQuery query) {
            return resolveDatasourceUid(
                    query != null ? query.getDatasourceUid() : "",
                    query != null ? query.getDatasource() : "");
        }

        private String resolveDatasourceUid(String literalUid, String namedDatasource) {
            if (literalUid != null && !literalUid.isBlank()) {
                return literalUid.trim();
            }
            if (namedDatasource != null && !namedDatasource.isBlank() && datasources != null) {
                String mapped = datasources.get(namedDatasource.trim());
                if (mapped != null && !mapped.isBlank()) {
                    return mapped.trim();
                }
            }
            return datasourceUid != null ? datasourceUid.trim() : "";
        }

        public boolean hasResolvableDatasource() {
            if (datasourceUid != null && !datasourceUid.isBlank()) {
                return true;
            }
            if (datasources == null) {
                return false;
            }
            return datasources.values().stream().anyMatch(v -> v != null && !v.isBlank());
        }
    }

    @Data
    public static class PrometheusMetricQuery {
        /** Logical datasource key — k8s, elasticsearch, mongo, ingress. */
        private String datasource = "";
        /** Optional literal UID; overrides {@link #datasource}. */
        private String datasourceUid = "";
        private String expr = "";
    }

    @Data
    public static class InfluxMetricQuery {
        /** Logical datasource key — mongo or elasticsearch (sensu-metrics InfluxDB). */
        private String datasource = "";
        private String datasourceUid = "";
        /** Required context host: {@code mongo} → {@code {mongoHost}}, {@code es} → {@code {esHost}}. */
        private String hostKey = "mongo";
        private String query = "";
        private boolean rawQuery = true;
    }

    public boolean useGrafanaStub() {
        if (!useSynthetic) {
            return false;
        }
        return grafana.isStub()
                || ((!hasGrafanaApiKey()) && !hasGrafanaSessionCookie());
    }

    private boolean hasGrafanaApiKey() {
        return grafana.getApiKey() != null && !grafana.getApiKey().isBlank();
    }

    private boolean hasGrafanaSessionCookie() {
        return grafana.getSessionCookie() != null && !grafana.getSessionCookie().isBlank();
    }

    public boolean useGraylogStub() {
        if (!useSynthetic) {
            return false;
        }
        return graylog.isStub() || !graylogConfigured();
    }

    public boolean graylogConfigured() {
        return graylog.getUrl() != null && !graylog.getUrl().isBlank()
                && (hasGraylogToken() || hasGraylogSessionCookie());
    }

    private boolean hasGraylogToken() {
        return graylog.getToken() != null && !graylog.getToken().isBlank();
    }

    private boolean hasGraylogSessionCookie() {
        return graylog.getSessionCookie() != null && !graylog.getSessionCookie().isBlank();
    }

    /** Skip duplicate error-index fetch when it matches the monitoring index pattern. */
    public boolean useSeparateKibanaErrorIndex() {
        String errorIndex = kibana.getErrorIndexPattern();
        if (errorIndex == null || errorIndex.isBlank()) {
            return false;
        }
        return !errorIndex.equals(kibana.getIndexPattern());
    }

    public boolean kibanaConfigured() {
        return kibana.getUrl() != null && !kibana.getUrl().isBlank()
                && kibana.getApiKey() != null && !kibana.getApiKey().isBlank();
    }

    @PostConstruct
    void normalizeConfig() {
        kibana.setUrl(stripQuotes(kibana.getUrl()).trim());
        kibana.setApiKey(stripQuotes(kibana.getApiKey()).trim());
        kibana.setIndexPattern(stripQuotes(kibana.getIndexPattern()));
        kibana.setErrorIndexPattern(stripQuotes(kibana.getErrorIndexPattern()));
        graylog.setUrl(stripQuotes(graylog.getUrl()).trim());
        graylog.setToken(stripQuotes(graylog.getToken()).trim());
        graylog.setSessionCookie(SessionCookieNormalizer.graylog(stripQuotes(graylog.getSessionCookie()).trim()));
        grafana.setUrl(stripQuotes(grafana.getUrl()).trim());
        grafana.setApiKey(stripQuotes(grafana.getApiKey()).trim());
        grafana.setSessionCookie(SessionCookieNormalizer.grafana(stripQuotes(grafana.getSessionCookie()).trim()));
        grafana.setDatasourceUid(stripQuotes(grafana.getDatasourceUid()).trim());
        grafana.setK8sNamespace(stripQuotes(grafana.getK8sNamespace()).trim());
        grafana.setIngressNamespace(stripQuotes(grafana.getIngressNamespace()).trim());
        grafana.setIngressControllerClass(stripQuotes(grafana.getIngressControllerClass()).trim());
        if (grafana.getIngressDatasourceByNamespace() == null) {
            grafana.setIngressDatasourceByNamespace(new LinkedHashMap<>());
        }
        if (grafana.getIngressDatasourceByNamespace().isEmpty()) {
            grafana.getIngressDatasourceByNamespace().put("ingress-nginx-webui", "ingress");
            grafana.getIngressDatasourceByNamespace().put("ingress-nginx-external", "ingress");
            grafana.getIngressDatasourceByNamespace().put("ingress-nginx-internal", "ingress-selenium");
        }
        if (grafana.getIngressDatasourceByNodePattern() == null) {
            grafana.setIngressDatasourceByNodePattern(new LinkedHashMap<>());
        }
        if (grafana.getIngressDatasourceByNodePattern().isEmpty()) {
            grafana.getIngressDatasourceByNodePattern().put("selenium-gke", "ingress-selenium");
            grafana.getIngressDatasourceByNodePattern().put("apps-gke", "ingress");
        }
        if (grafana.getK8sNamespace().isBlank()) {
            grafana.setK8sNamespace("spr-apps");
        }
        if (grafana.getIngressNamespace().isBlank()) {
            grafana.setIngressNamespace("ingress-nginx-external");
        }
        if (grafana.getDatasources() == null) {
            grafana.setDatasources(new LinkedHashMap<>());
        }
        Map<String, String> grafanaDs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : grafana.getDatasources().entrySet()) {
            String uid = stripQuotes(entry.getValue()).trim();
            if (!uid.isBlank()) {
                grafanaDs.put(entry.getKey(), uid);
            }
        }
        if (!grafana.getDatasourceUid().isBlank()) {
            grafanaDs.putIfAbsent("k8s", grafana.getDatasourceUid());
            grafanaDs.putIfAbsent("ingress", grafana.getDatasourceUid());
        }
        grafana.setDatasources(grafanaDs);

        if (kibanaConfigured()) {
            log.info("Observability Kibana: index={} errorIndex={} useSynthetic={} graylogStub={} grafanaStub={} grafanaDatasources={} graylogCookieLen={} grafanaCookieLen={}",
                    kibana.getIndexPattern(),
                    kibana.getErrorIndexPattern().isBlank() ? "(none)" : kibana.getErrorIndexPattern(),
                    useSynthetic,
                    useGraylogStub(),
                    useGrafanaStub(),
                    grafana.getDatasources().keySet(),
                    graylog.getSessionCookie() != null ? graylog.getSessionCookie().length() : 0,
                    grafana.getSessionCookie() != null ? grafana.getSessionCookie().length() : 0);
        } else {
            log.warn("Kibana not configured — set KIBANA_URL and KIBANA_API_KEY in .env");
        }
    }

    /** Spring .env import may preserve shell quotes around wildcards — strip them. */
    static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
