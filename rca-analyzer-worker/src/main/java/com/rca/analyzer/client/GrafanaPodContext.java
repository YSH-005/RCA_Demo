package com.rca.analyzer.client;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Kubernetes pod naming from Graylog {@code source} / Kibana {@code hostNameIp}.
 * Matches Grafana Kubernetes-Pod-Stats-V2 variables:
 * {@code kubernetes_pod_name}, {@code deployment} (ReplicaSet), {@code Deployment_id} (base deployment).
 */
public final class GrafanaPodContext {

    private static final Pattern POD_SUFFIX = Pattern.compile("-[a-z0-9]{4,6}$");
    private static final Pattern RS_SUFFIX = Pattern.compile("-[a-z0-9]{8,10}$");

    private GrafanaPodContext() {
    }

    public static Parsed parse(String podName, String namespace) {
        String pod = podName != null ? podName.trim() : "";
        String ns = namespace != null && !namespace.isBlank() ? namespace.trim() : "spr-apps";
        if (pod.isBlank() || "unknown-pod".equals(pod)) {
            return new Parsed("", ns, "", "");
        }
        String deploymentId = stripPodSuffix(pod);
        String deployment = stripReplicaSetSuffix(deploymentId);
        return new Parsed(pod, ns, deploymentId, deployment);
    }

    /**
     * Prefer namespace from Graylog {@code k8s_namespace} / Kibana logs, else configured default.
     */
    public static String resolveNamespace(
            List<Map<String, Object>> graylogLogs,
            List<Map<String, Object>> kibanaLogs,
            String fallback) {
        String fromGraylog = firstNamespace(graylogLogs, "k8sNamespace", "k8s_namespace");
        if (!fromGraylog.isBlank()) {
            return fromGraylog;
        }
        String fromKibana = firstNamespace(kibanaLogs, "k8sNamespace", "namespace", "k8s_namespace");
        if (!fromKibana.isBlank()) {
            return fromKibana;
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : "spr-apps";
    }

    private static String firstNamespace(List<Map<String, Object>> logs, String... keys) {
        if (logs == null) {
            return "";
        }
        for (Map<String, Object> log : logs) {
            if (log == null) {
                continue;
            }
            for (String key : keys) {
                Object value = log.get(key);
                if (value != null) {
                    String ns = String.valueOf(value).trim();
                    if (!ns.isBlank()) {
                        return ns;
                    }
                }
            }
        }
        return "";
    }

    /** Full pod name, e.g. webui-app-deployment-54b9cbb57-9srbb */
    public static String stripPodSuffix(String pod) {
        Matcher m = POD_SUFFIX.matcher(pod);
        if (m.find()) {
            return pod.substring(0, m.start());
        }
        return pod;
    }

    /** Base deployment name, e.g. webui-app-deployment */
    public static String stripReplicaSetSuffix(String deploymentId) {
        Matcher m = RS_SUFFIX.matcher(deploymentId);
        if (m.find()) {
            return deploymentId.substring(0, m.start());
        }
        return deploymentId;
    }

    public record Parsed(String podName, String namespace, String deploymentId, String deployment) {
    }
}
