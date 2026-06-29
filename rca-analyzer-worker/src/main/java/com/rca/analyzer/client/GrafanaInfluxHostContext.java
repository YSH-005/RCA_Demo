package com.rca.analyzer.client;

/**
 * Normalizes monitoring FQDNs to Influx {@code host} tags used as Grafana {@code $hostname}.
 */
public final class GrafanaInfluxHostContext {

    private GrafanaInfluxHostContext() {
    }

    /** FQDN → short host, e.g. {@code es-data-qa6-core3-es-5-b.sprinklr.com} → {@code es-data-qa6-core3-es-5-b}. */
    public static String toInfluxHostname(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String trimmed = host.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    /**
     * Graylog calltracking often reports {@code qa6-mongo-core-40.sprinklr.com} while Influx uses
     * {@code qa6-mongo-core-40-b}. Until a shard map exists, strip the domain and use the short
     * name as a prefix for Influx {@code host =~ /^prefix/} queries (first matching host wins).
     */
    public static String toMongoHostPrefix(String host) {
        return toInfluxHostname(host);
    }

    /** Same prefix strip as mongo — Graylog/Kibana FQDN → Influx {@code host =~ /^prefix/}. */
    public static String toEsHostPrefix(String host) {
        return toInfluxHostname(host);
    }
}
