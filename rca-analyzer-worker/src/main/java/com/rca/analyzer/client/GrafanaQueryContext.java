package com.rca.analyzer.client;

/**
 * Placeholders substituted into Prometheus query templates.
 */
public record GrafanaQueryContext(
        String podName,
        String namespace,
        String deploymentId,
        String deployment,
        String esHost,
        String mongoHost,
        String controllerPod,
        String ingressNamespace,
        String controllerClass,
        String ingressName,
        String k8sNode) {

    public static GrafanaQueryContext of(String podName) {
        return of(podName, "spr-apps", "", "", GrafanaIngressContext.Resolved.empty());
    }

    public static GrafanaQueryContext of(String podName, String esHost, String mongoHost) {
        return of(podName, "spr-apps", esHost, mongoHost, GrafanaIngressContext.Resolved.empty());
    }

    public static GrafanaQueryContext of(
            String podName,
            String k8sNamespace,
            String esHost,
            String mongoHost,
            GrafanaIngressContext.Resolved ingress) {

        GrafanaPodContext.Parsed parsed = GrafanaPodContext.parse(podName, k8sNamespace);
        GrafanaIngressContext.Resolved ing = ingress != null ? ingress : GrafanaIngressContext.Resolved.empty();
        return new GrafanaQueryContext(
                parsed.podName(),
                parsed.namespace(),
                parsed.deploymentId(),
                parsed.deployment(),
                esHost != null ? esHost : "",
                mongoHost != null ? mongoHost : "",
                ing.controllerPod(),
                ing.ingressNamespace(),
                ing.controllerClass(),
                ing.ingressName(),
                ing.k8sNode());
    }

    public GrafanaQueryContext withMongoHost(String host) {
        return withHosts(esHost, host);
    }

    public GrafanaQueryContext withEsHost(String host) {
        return withHosts(host != null ? host : "", mongoHost);
    }

    private GrafanaQueryContext withHosts(String es, String mongo) {
        return new GrafanaQueryContext(
                podName, namespace, deploymentId, deployment,
                es != null ? es : "",
                mongo != null ? mongo : "",
                controllerPod, ingressNamespace, controllerClass, ingressName, k8sNode);
    }
}
