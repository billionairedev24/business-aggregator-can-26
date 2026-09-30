package ca.northline.merchants.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link KubeApi} over the Kubernetes REST API with the pod's ServiceAccount: the projected token is re-read on every
 * call (the kubelet rotates it), the API server's certificate is checked against the mounted cluster CA. No client
 * library, no cloud SDK — the same on EKS, GKE, AKS and kind. RBAC (the chart's Role) limits it to Gateways,
 * HTTPRoutes and Certificates in the app's own namespace.
 */
final class HttpKubeApi implements KubeApi {

    static final String FIELD_MANAGER = "northline-domains";
    static final String APPLY_PATCH = "application/apply-patch+yaml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    interface Api {
        @GetExchange("/apis/{group}/{version}/namespaces/{namespace}/{plural}")
        JsonNode list(
                @PathVariable String group,
                @PathVariable String version,
                @PathVariable String namespace,
                @PathVariable String plural,
                @RequestParam String labelSelector,
                @RequestHeader("Authorization") String authorization);

        /** Server-side apply; the body is JSON, which is valid YAML. */
        @PatchExchange(
                url = "/apis/{group}/{version}/namespaces/{namespace}/{plural}/{name}",
                contentType = APPLY_PATCH)
        JsonNode apply(
                @PathVariable String group,
                @PathVariable String version,
                @PathVariable String namespace,
                @PathVariable String plural,
                @PathVariable String name,
                @RequestParam String fieldManager,
                @RequestParam boolean force,
                @RequestBody String body,
                @RequestHeader("Authorization") String authorization);

        @DeleteExchange("/apis/{group}/{version}/namespaces/{namespace}/{plural}/{name}")
        void delete(
                @PathVariable String group,
                @PathVariable String version,
                @PathVariable String namespace,
                @PathVariable String plural,
                @PathVariable String name,
                @RequestHeader("Authorization") String authorization);
    }

    private final Api api;
    private final String namespace;
    private final Path token;

    HttpKubeApi(URI apiServer, String namespace, Path token, Path caCert) {
        this.namespace = namespace;
        this.token = token;
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if ("https".equals(apiServer.getScheme()) && Files.isReadable(caCert)) {
            http.sslContext(trusting(caCert));
        }
        var requests = new JdkClientHttpRequestFactory(http.build());
        requests.setReadTimeout(Duration.ofSeconds(20));
        var rest = RestClient.builder()
                .baseUrl(apiServer.toString())
                .requestFactory(requests)
                .build();
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(Api.class);
    }

    @Override
    public List<JsonNode> list(Kind kind, String labelSelector) {
        var items = new ArrayList<JsonNode>();
        api.list(kind.group, kind.version, namespace, kind.plural, labelSelector, bearer())
                .path("items")
                .forEach(items::add);
        return items;
    }

    @Override
    public void apply(Kind kind, ObjectNode object) {
        var name = object.path("metadata").path("name").asString();
        api.apply(
                kind.group,
                kind.version,
                namespace,
                kind.plural,
                name,
                FIELD_MANAGER,
                true,
                JSON.writeValueAsString(object),
                bearer());
    }

    @Override
    public void delete(Kind kind, String name) {
        try {
            api.delete(kind.group, kind.version, namespace, kind.plural, name, bearer());
        } catch (HttpClientErrorException.NotFound _) {
            // already gone
        }
    }

    private String bearer() {
        try {
            return "Bearer " + Files.readString(token).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("ServiceAccount token not readable at " + token, e);
        }
    }

    private static SSLContext trusting(Path caCert) {
        try (InputStream in = Files.newInputStream(caCert)) {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            var n = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("ca-" + n++, cert);
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cluster CA not usable at " + caCert, e);
        }
    }
}
