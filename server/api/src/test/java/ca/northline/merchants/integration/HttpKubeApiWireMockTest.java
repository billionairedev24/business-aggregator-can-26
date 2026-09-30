package ca.northline.merchants.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.integration.KubeApi.Kind;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S-31: the reconciler's Kubernetes calls as the API server documents them (REST paths, label selectors, server-side
 * apply with a field manager, the rotating ServiceAccount token) — against WireMock, not a cluster.
 */
class HttpKubeApiWireMockTest {

    static WireMockServer server;

    @TempDir
    static Path dir;

    static final String CERTS = "/apis/cert-manager.io/v1/namespaces/northline-prod/certificates";
    static final String GATEWAYS = "/apis/gateway.networking.k8s.io/v1/namespaces/northline-prod/gateways";

    @BeforeAll
    static void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    HttpKubeApi api(Path token) {
        return new HttpKubeApi(URI.create(server.baseUrl()), "northline-prod", token, dir.resolve("no-ca.crt"));
    }

    @Test
    void listsByLabel_appliesServerSide_deletes_withTheCurrentToken() throws Exception {
        var token = Files.writeString(dir.resolve("token"), "fake-sa-token-1\n");
        server.stubFor(get(urlPathEqualTo(CERTS))
                .withQueryParam("labelSelector", equalTo("northline.ca/custom-domain=true"))
                .willReturn(okJson("""
                        {"kind":"CertificateList","items":[{"metadata":{"name":"nl-cd-1"}},{"metadata":{"name":"nl-cd-2"}}]}""")));
        server.stubFor(patch(urlPathEqualTo(GATEWAYS + "/northline-custom-0")).willReturn(okJson("{}")));
        server.stubFor(delete(urlPathEqualTo(CERTS + "/nl-cd-1")).willReturn(okJson("{}")));
        server.stubFor(delete(urlPathEqualTo(CERTS + "/nl-cd-gone"))
                .willReturn(aResponse().withStatus(404).withBody("{\"kind\":\"Status\",\"code\":404}")));

        var kube = api(token);
        assertThat(kube.list(Kind.CERTIFICATE, "northline.ca/custom-domain=true"))
                .extracting(n -> n.path("metadata").path("name").asString())
                .containsExactly("nl-cd-1", "nl-cd-2");

        Files.writeString(token, "fake-sa-token-2"); // the kubelet rotated it
        var edge = new GatewayDomainEdge(
                new FakeKubeApi(),
                new GatewayDomainEdge.Settings(
                        "envoy",
                        "northline-custom",
                        64,
                        1000,
                        "northline-acme-custom",
                        "Issuer",
                        "northline-consumer",
                        3000,
                        "720h",
                        DomainsConfig.DEFAULT_HEADERS));
        kube.apply(Kind.GATEWAY, edge.gateway(0, Set.of("book.aspen.ca")));
        kube.delete(Kind.CERTIFICATE, "nl-cd-1");
        kube.delete(Kind.CERTIFICATE, "nl-cd-gone"); // already gone: fine

        server.verify(
                getRequestedFor(urlPathEqualTo(CERTS)).withHeader("Authorization", equalTo("Bearer fake-sa-token-1")));
        server.verify(patchRequestedFor(urlPathEqualTo(GATEWAYS + "/northline-custom-0"))
                .withQueryParam("fieldManager", equalTo("northline-domains"))
                .withQueryParam("force", equalTo("true"))
                .withHeader("Content-Type", equalTo("application/apply-patch+yaml"))
                .withHeader("Authorization", equalTo("Bearer fake-sa-token-2"))
                .withRequestBody(equalToJson("""
                        {"apiVersion":"gateway.networking.k8s.io/v1","kind":"Gateway",
                         "metadata":{"name":"northline-custom-0"},
                         "spec":{"gatewayClassName":"envoy","listeners":[{"hostname":"book.aspen.ca","port":443}]}}""", true, true)));
        server.verify(deleteRequestedFor(urlPathEqualTo(CERTS + "/nl-cd-1")));
    }
}
