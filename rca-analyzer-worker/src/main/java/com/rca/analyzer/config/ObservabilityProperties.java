package com.rca.analyzer.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private Graylog graylog = new Graylog();
    private Kibana kibana = new Kibana();
    private Grafana grafana = new Grafana();

    @Data
    public static class Graylog {
        private String url = "";
        private String token = "";
        /** Use stub when true or when real API is unreachable (SSO gateway). */
        private boolean stub = true;
        private int maxResults = 50;
        /** Optional stream filter, e.g. streams:calltrackingbydb OR streams:perfstats */
        private String streamFilter = "streams:calltrackingbydb OR streams:perfstats";
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
        /** Use stub when true or when api-key is blank. */
        private boolean stub = true;
        private String datasourceType = "influxdb";
        private String datasourceUid = "";
        /** Flux query with {podName}, {from}, {to} placeholders. */
        private String fluxQueryTemplate = """
                from(bucket: "metrics")
                  |> range(start: {from}, stop: {to})
                  |> filter(fn: (r) => r["pod"] == "{podName}")
                """;
    }

    public boolean useGrafanaStub() {
        return grafana.isStub() || grafana.getApiKey() == null || grafana.getApiKey().isBlank();
    }

    public boolean useGraylogStub() {
        return graylog.isStub() || graylog.getToken() == null || graylog.getToken().isBlank();
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
        grafana.setUrl(stripQuotes(grafana.getUrl()).trim());
        grafana.setApiKey(stripQuotes(grafana.getApiKey()).trim());

        if (kibanaConfigured()) {
            log.info("Observability Kibana: index={} errorIndex={} graylogStub={} grafanaStub={}",
                    kibana.getIndexPattern(),
                    kibana.getErrorIndexPattern().isBlank() ? "(none)" : kibana.getErrorIndexPattern(),
                    useGraylogStub(),
                    useGrafanaStub());
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
