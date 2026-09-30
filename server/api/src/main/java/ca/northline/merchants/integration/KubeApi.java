package ca.northline.merchants.integration;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The few Kubernetes API calls the custom-domain reconciler makes, all in one namespace: list by label, server-side
 * apply, delete. {@link HttpKubeApi} talks to the API server; tests use an in-memory fake.
 */
interface KubeApi {

    /** The kinds the reconciler writes (the chart's Role grants exactly these). */
    enum Kind {
        GATEWAY("gateway.networking.k8s.io", "v1", "gateways", "Gateway"),
        HTTP_ROUTE("gateway.networking.k8s.io", "v1", "httproutes", "HTTPRoute"),
        CERTIFICATE("cert-manager.io", "v1", "certificates", "Certificate");

        final String group;
        final String version;
        final String plural;
        final String kind;

        Kind(String group, String version, String plural, String kind) {
            this.group = group;
            this.version = version;
            this.plural = plural;
            this.kind = kind;
        }

        String apiVersion() {
            return group + "/" + version;
        }
    }

    /** Objects of {@code kind} in the namespace whose labels match {@code labelSelector}. */
    List<JsonNode> list(Kind kind, String labelSelector);

    /** Server-side apply of a whole object (field manager {@code northline-domains}, conflicts forced). */
    void apply(Kind kind, ObjectNode object);

    /** Deletes by name; an object that is already gone is fine. */
    void delete(Kind kind, String name);
}
