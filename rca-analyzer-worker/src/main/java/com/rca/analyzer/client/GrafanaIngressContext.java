package com.rca.analyzer.client;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Ingress Review dashboard variables from Graylog nginx controller / access logs.
 */
public final class GrafanaIngressContext {

    private static final Pattern CONTROLLER_NAMESPACE =
            Pattern.compile("^nginx-ingress-([a-z0-9-]+)-controller-");

    public static boolean isIngressLog(String logKind) {
        return "ingress".equals(logKind) || "ingress_controller".equals(logKind);
    }

    private GrafanaIngressContext() {
    }

    public record Resolved(
            String controllerPod,
            String ingressNamespace,
            String controllerClass,
            String ingressName,
            String k8sNode) {

        public static Resolved empty() {
            return new Resolved("", "", "", "", "");
        }
    }

    public static Resolved resolve(
            List<Map<String, Object>> graylogLogs,
            String defaultNamespace,
            String defaultControllerClass) {

        String controllerPod = "";
        String ingressNamespace = "";
        String controllerClass = defaultControllerClass != null ? defaultControllerClass : "";
        String ingressName = "";
        String k8sNode = "";

        if (graylogLogs != null) {
            for (Map<String, Object> log : graylogLogs) {
                controllerPod = firstNonBlank(controllerPod, str(log.get("controllerPod")));
                ingressNamespace = firstNonBlank(ingressNamespace, str(log.get("ingressNamespace")));
                controllerClass = firstNonBlank(controllerClass, str(log.get("controllerClass")));
                ingressName = firstNonBlank(ingressName, str(log.get("ingressName")));
                k8sNode = firstNonBlank(k8sNode, str(log.get("k8sNode")));

                String hostname = firstNonBlank(str(log.get("hostname")), str(log.get("podName")));
                if (controllerPod.isBlank() && isNginxController(hostname)) {
                    controllerPod = hostname;
                }
                if (ingressNamespace.isBlank() && isNginxController(controllerPod)) {
                    ingressNamespace = deriveControllerNamespace(controllerPod);
                }
                String k8sNs = str(log.get("k8sNamespace"));
                if (ingressNamespace.isBlank() && k8sNs.contains("ingress-nginx")) {
                    ingressNamespace = k8sNs;
                }
            }
        }

        if (ingressNamespace.isBlank() && defaultNamespace != null && defaultNamespace.contains("ingress-nginx")) {
            ingressNamespace = defaultNamespace;
        }
        if (ingressNamespace.isBlank() && isNginxController(controllerPod)) {
            ingressNamespace = deriveControllerNamespace(controllerPod);
        }

        return new Resolved(
                controllerPod.trim(),
                ingressNamespace.trim(),
                controllerClass.trim(),
                ingressName.trim(),
                k8sNode.trim());
    }

    /**
     * Maps nginx-ingress-webui-controller-abc123 → ingress-nginx-webui (Ingress Review $namespace).
     */
    static String deriveControllerNamespace(String controllerPod) {
        if (controllerPod == null || controllerPod.isBlank()) {
            return "";
        }
        Matcher matcher = CONTROLLER_NAMESPACE.matcher(controllerPod.trim());
        if (!matcher.find()) {
            return "";
        }
        return "ingress-nginx-" + matcher.group(1);
    }

    static boolean isNginxController(String hostname) {
        return hostname != null && hostname.startsWith("nginx-ingress");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null ? b : "";
    }
}
