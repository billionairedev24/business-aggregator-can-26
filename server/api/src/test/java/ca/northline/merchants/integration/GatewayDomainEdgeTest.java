package ca.northline.merchants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.domain.DomainProblem;
import ca.northline.merchants.domain.EdgeObservation;
import ca.northline.merchants.integration.KubeApi.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** S-31: the Gateway API reconciler against an in-memory namespace (no cluster). */
class GatewayDomainEdgeTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    FakeKubeApi kube;
    GatewayDomainEdge edge;

    @BeforeEach
    void setUp() {
        kube = new FakeKubeApi();
        edge = edge(64, 1000);
    }

    GatewayDomainEdge edge(int listenersPerGateway, int maxDomains) {
        return new GatewayDomainEdge(
                kube,
                new GatewayDomainEdge.Settings(
                        "envoy",
                        "northline-custom",
                        listenersPerGateway,
                        maxDomains,
                        "northline-acme-custom",
                        "Issuer",
                        "northline-consumer",
                        3000,
                        "720h",
                        DomainsConfig.DEFAULT_HEADERS));
    }

    static Set<String> hosts(int n) {
        return Set.copyOf(
                IntStream.range(0, n).mapToObj(i -> "shop" + i + ".example.ca").toList());
    }

    List<String> listenerHosts(String gateway) {
        return kube.get(Kind.GATEWAY, gateway)
                .path("spec")
                .path("listeners")
                .valueStream()
                .map(l -> l.path("hostname").asString())
                .toList();
    }

    /** What cert-manager and Envoy Gateway would write once a certificate is issued. */
    void issued(String host) {
        kube.get(Kind.CERTIFICATE, GatewayDomainEdge.objectName(host)).set("status", JSON.readTree("""
                        {"conditions":[{"type":"Ready","status":"True","reason":"Ready"}],"notAfter":"2027-01-01T00:00:00Z"}"""));
        var gateway = kube.all(Kind.GATEWAY).stream()
                .filter(g -> g.path("spec")
                        .path("listeners")
                        .valueStream()
                        .anyMatch(l -> host.equals(l.path("hostname").asString())))
                .findFirst()
                .orElseThrow();
        var status = gateway.has("status") ? (ObjectNode) gateway.get("status") : gateway.putObject("status");
        var listeners = status.has("listeners") ? (ArrayNode) status.get("listeners") : status.putArray("listeners");
        listeners
                .addObject()
                .put("name", GatewayDomainEdge.listenerName(host))
                .putArray("conditions")
                .addObject()
                .put("type", "Programmed")
                .put("status", "True");
    }

    @Test
    void newDomains_aListenerACertificateAndARoute_each() {
        var seen = edge.reconcile(Set.of("book.aspen.ca", "shop.pho.ca"), 10);

        assertThat(seen)
                .containsEntry("book.aspen.ca", new EdgeObservation.Provisioning(true))
                .containsEntry("shop.pho.ca", new EdgeObservation.Provisioning(true));
        var gateway = kube.get(Kind.GATEWAY, "northline-custom-0");
        assertThat(gateway.path("spec").path("gatewayClassName").asString()).isEqualTo("envoy");
        assertThat(gateway.path("metadata")
                        .path("labels")
                        .path("northline.ca/custom-domain")
                        .asString())
                .isEqualTo("true");
        assertThat(listenerHosts("northline-custom-0")).containsExactlyInAnyOrder("book.aspen.ca", "shop.pho.ca");
        var listener = gateway.path("spec")
                .path("listeners")
                .valueStream()
                .filter(l -> "book.aspen.ca".equals(l.path("hostname").asString()))
                .findFirst()
                .orElseThrow();
        assertThat(listener.path("protocol").asString()).isEqualTo("HTTPS");
        assertThat(listener.path("port").asInt()).isEqualTo(443);
        assertThat(listener.path("tls")
                        .path("certificateRefs")
                        .get(0)
                        .path("name")
                        .asString())
                .isEqualTo(GatewayDomainEdge.secretName("book.aspen.ca"));

        var certificate = kube.get(Kind.CERTIFICATE, GatewayDomainEdge.objectName("book.aspen.ca"));
        assertThat(certificate.path("spec").path("dnsNames").get(0).asString()).isEqualTo("book.aspen.ca");
        assertThat(certificate.path("spec").path("issuerRef").path("name").asString())
                .isEqualTo("northline-acme-custom");
        assertThat(certificate.path("spec").path("privateKey").path("algorithm").asString())
                .isEqualTo("ECDSA");
        assertThat(certificate
                        .path("metadata")
                        .path("annotations")
                        .path("northline.ca/host")
                        .asString())
                .isEqualTo("book.aspen.ca");

        var route = kube.get(Kind.HTTP_ROUTE, GatewayDomainEdge.objectName("book.aspen.ca"));
        assertThat(route.path("spec").path("hostnames").get(0).asString()).isEqualTo("book.aspen.ca");
        assertThat(route.path("spec").path("parentRefs").get(0).path("name").asString())
                .isEqualTo("northline-custom-0");
        assertThat(route.path("spec")
                        .path("parentRefs")
                        .get(0)
                        .path("sectionName")
                        .asString())
                .isEqualTo(GatewayDomainEdge.listenerName("book.aspen.ca"));
        assertThat(route.path("metadata")
                        .path("annotations")
                        .path("external-dns.alpha.kubernetes.io/controller")
                        .asString())
                .isEqualTo("none");
        var rule = route.path("spec").path("rules").get(0);
        assertThat(rule.path("backendRefs").get(0).path("name").asString()).isEqualTo("northline-consumer");
        assertThat(rule.path("backendRefs").get(0).path("port").asInt()).isEqualTo(3000);
        assertThat(rule.path("filters")
                        .get(0)
                        .path("responseHeaderModifier")
                        .path("set")
                        .valueStream()
                        .map(h -> h.path("name").asString() + ": "
                                + h.path("value").asString()))
                .contains("Strict-Transport-Security: max-age=63072000", "X-Content-Type-Options: nosniff");
    }

    @Test
    void issued_ready_andAQuietReconcileWritesNothing() {
        edge.reconcile(Set.of("book.aspen.ca"), 10);
        assertThat(edge.reconcile(Set.of("book.aspen.ca"), 10))
                .containsEntry("book.aspen.ca", new EdgeObservation.Provisioning(false));
        issued("book.aspen.ca");

        kube.calls.clear();
        assertThat(edge.reconcile(Set.of("book.aspen.ca"), 10)).containsEntry("book.aspen.ca", EdgeObservation.READY);
        assertThat(kube.calls).as("nothing changed, nothing applied").isEmpty();
    }

    @Test
    void certificateIssuedButListenerNotProgrammedYet_stillProvisioning() {
        edge.reconcile(Set.of("book.aspen.ca"), 10);
        kube.get(Kind.CERTIFICATE, GatewayDomainEdge.objectName("book.aspen.ca"))
                .set("status", JSON.readTree("{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}"));
        assertThat(edge.reconcile(Set.of("book.aspen.ca"), 10))
                .containsEntry("book.aspen.ca", new EdgeObservation.Provisioning(false));
    }

    @Test
    void certManagerGaveUp_failedWithItsMessage() {
        edge.reconcile(Set.of("book.aspen.ca"), 10);
        kube.get(Kind.CERTIFICATE, GatewayDomainEdge.objectName("book.aspen.ca"))
                .set("status", JSON.readTree("""
                {"lastFailureTime":"2026-10-01T15:05:00Z","failedIssuanceAttempts":1,
                 "conditions":[{"type":"Ready","status":"False","reason":"DoesNotExist","message":"Issuing certificate"},
                               {"type":"Issuing","status":"False","reason":"Failed",
                                "message":"The certificate request has failed to complete and will be retried: 403 urn:ietf:params:acme:error:unauthorized"}]}"""));
        var seen = edge.reconcile(Set.of("book.aspen.ca"), 10).get("book.aspen.ca");
        assertThat(seen)
                .isInstanceOfSatisfying(
                        EdgeObservation.Failed.class,
                        f -> assertThat(f.reason()).contains("acme:error:unauthorized"));
    }

    @Test
    void notWantedAnyMore_everythingRemoved_emptyShardDeleted() {
        edge.reconcile(Set.of("book.aspen.ca", "shop.pho.ca"), 10);
        var seen = edge.reconcile(Set.of("shop.pho.ca"), 10);
        assertThat(seen).containsEntry("book.aspen.ca", EdgeObservation.ABSENT);
        assertThat(listenerHosts("northline-custom-0")).containsExactly("shop.pho.ca");
        assertThat(kube.get(Kind.CERTIFICATE, GatewayDomainEdge.objectName("book.aspen.ca")))
                .isNull();
        assertThat(kube.get(Kind.HTTP_ROUTE, GatewayDomainEdge.objectName("book.aspen.ca")))
                .isNull();

        edge.reconcile(Set.of(), 10);
        assertThat(kube.all(Kind.GATEWAY)).isEmpty();
        assertThat(kube.all(Kind.CERTIFICATE)).isEmpty();
        assertThat(kube.all(Kind.HTTP_ROUTE)).isEmpty();
    }

    @Test
    void past64Listeners_anotherShard_domainsStayWhereTheyAre_freedSlotsReused() {
        var many = hosts(150);
        var seen = edge.reconcile(many, 1000);
        assertThat(seen.values()).allMatch(o -> o.equals(new EdgeObservation.Provisioning(true)));
        assertThat(kube.all(Kind.GATEWAY))
                .extracting(g -> g.path("metadata").path("name").asString())
                .containsExactly("northline-custom-0", "northline-custom-1", "northline-custom-2");
        assertThat(listenerHosts("northline-custom-0")).hasSize(64);
        assertThat(listenerHosts("northline-custom-1")).hasSize(64);
        assertThat(listenerHosts("northline-custom-2")).hasSize(22);
        assertThat(kube.all(Kind.GATEWAY))
                .allMatch(g -> g.path("spec").path("listeners").size() <= 64, "Gateway API: at most 64 listeners");

        var gone = listenerHosts("northline-custom-0").getFirst();
        var fewer = new java.util.HashSet<>(many);
        fewer.remove(gone);
        fewer.add("new.example.ca");
        var before1 = listenerHosts("northline-custom-1");
        edge.reconcile(fewer, 10);
        assertThat(listenerHosts("northline-custom-0"))
                .contains("new.example.ca")
                .doesNotContain(gone)
                .hasSize(64);
        assertThat(listenerHosts("northline-custom-1")).isEqualTo(before1);
    }

    @Test
    void newCertificatesRationed_andCapacity() {
        var seen = edge.reconcile(hosts(5), 2);
        assertThat(seen.values().stream().filter(o -> o.equals(new EdgeObservation.Provisioning(true))))
                .hasSize(2);
        assertThat(seen.values().stream()
                        .filter(o -> o.equals(new EdgeObservation.Deferred(DomainProblem.RATE_LIMITED))))
                .hasSize(3);
        assertThat(kube.all(Kind.CERTIFICATE)).hasSize(2);

        var small = edge(64, 3);
        var next = small.reconcile(hosts(5), 10);
        assertThat(next.values().stream().filter(o -> o.equals(new EdgeObservation.Deferred(DomainProblem.CAPACITY))))
                .hasSize(2);
        assertThat(kube.all(Kind.CERTIFICATE)).hasSize(3);
    }

    @Test
    void leavesOtherObjectsAlone() {
        var mainGateway = JSON.createObjectNode();
        mainGateway
                .putObject("metadata")
                .put("name", "northline")
                .putObject("labels")
                .put("app", "edge");
        kube.objects
                .computeIfAbsent(Kind.GATEWAY, _ -> new java.util.LinkedHashMap<>())
                .put("northline", mainGateway);
        edge.reconcile(Set.of("book.aspen.ca"), 10);
        edge.reconcile(Set.of(), 10);
        assertThat(kube.get(Kind.GATEWAY, "northline")).isNotNull();
        assertThat(kube.calls).noneMatch(c -> c.endsWith(" northline"));
    }

    @Test
    void namesAreStableAndDnsSafe() {
        var long63 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + ".example.ca";
        for (var name : List.of(
                GatewayDomainEdge.objectName(long63),
                GatewayDomainEdge.secretName(long63),
                GatewayDomainEdge.listenerName(long63))) {
            assertThat(name).matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?").hasSizeLessThanOrEqualTo(63);
        }
        assertThat(GatewayDomainEdge.objectName("book.aspen.ca"))
                .isEqualTo(GatewayDomainEdge.objectName("book.aspen.ca"));
        assertThat(DomainsConfig.headers("{\"X-Frame-Options\":\"DENY\"}"))
                .isEqualTo(Map.of("X-Frame-Options", "DENY"));
    }
}
