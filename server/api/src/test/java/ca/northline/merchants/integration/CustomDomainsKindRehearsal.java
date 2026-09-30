package ca.northline.merchants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.domain.EdgeObservation;
import ca.northline.merchants.integration.KubeApi.Kind;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * S-31 kind rehearsal (deploy/kind/custom-domains.sh): the reconciler against a real API server, cert-manager and Envoy
 * Gateway, authenticated as the chart's {@code northline-api} ServiceAccount (so the chart's Role is what allows it).
 * Skipped unless {@code NL_KIND_API} is set. {@code NL_KIND_STEP=add} serves {@code NL_KIND_HOSTS} domains on shards of
 * {@code NL_KIND_LISTENERS} listeners and waits until every one is ready; {@code remove} takes them all away again.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "NL_KIND_API", matches = ".+")
class CustomDomainsKindRehearsal {

    static String env(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }

    static KubeApi kube() {
        return new HttpKubeApi(
                URI.create(env("NL_KIND_API")),
                env("NL_KIND_NAMESPACE"),
                Path.of(env("NL_KIND_TOKEN")),
                Path.of(env("NL_KIND_CA")));
    }

    static GatewayDomainEdge edge(KubeApi kube) {
        return new GatewayDomainEdge(
                kube,
                new GatewayDomainEdge.Settings(
                        "envoy",
                        "northline-custom",
                        listeners(), // NL_KIND_LISTENERS, default 2: a handful of domains spans several merged Gateways
                        100,
                        "northline-acme-custom",
                        "Issuer",
                        "northline-consumer",
                        3000,
                        "720h",
                        DomainsConfig.DEFAULT_HEADERS));
    }

    static int listeners() {
        return Integer.parseInt(System.getenv().getOrDefault("NL_KIND_LISTENERS", "2"));
    }

    static Set<String> hosts() {
        var n = Integer.parseInt(System.getenv().getOrDefault("NL_KIND_HOSTS", "5"));
        var hosts = new LinkedHashSet<String>();
        IntStream.range(0, n).forEach(i -> hosts.add("shop" + i + ".merchant.kind.test"));
        return hosts;
    }

    @Test
    void rehearse() throws InterruptedException {
        var kube = kube();
        var edge = edge(kube);
        var hosts = hosts();
        if ("remove".equals(System.getenv("NL_KIND_STEP"))) {
            var seen = edge.reconcile(Set.of(), 0);
            assertThat(seen.values()).containsOnly(EdgeObservation.ABSENT);
            assertThat(kube.list(Kind.GATEWAY, GatewayDomainEdge.SELECTOR)).isEmpty();
            assertThat(kube.list(Kind.CERTIFICATE, GatewayDomainEdge.SELECTOR)).isEmpty();
            assertThat(kube.list(Kind.HTTP_ROUTE, GatewayDomainEdge.SELECTOR)).isEmpty();
            log.info("REHEARSAL removed {}", seen.keySet());
            return;
        }
        var first = edge.reconcile(hosts, hosts.size());
        log.info("REHEARSAL first reconcile {}", first);
        assertThat(kube.list(Kind.GATEWAY, GatewayDomainEdge.SELECTOR))
                .hasSize((hosts.size() + listeners() - 1) / listeners());

        var deadline = Instant.now().plus(Duration.ofMinutes(4));
        Map<String, EdgeObservation> seen = first;
        while (Instant.now().isBefore(deadline)) {
            seen = edge.reconcile(hosts, 0);
            if (seen.values().stream().allMatch(EdgeObservation.READY::equals)) {
                break;
            }
            Thread.sleep(3000);
        }
        log.info(
                "REHEARSAL after {} s: {}",
                Duration.between(deadline.minus(Duration.ofMinutes(4)), Instant.now())
                        .toSeconds(),
                seen);
        assertThat(seen).hasSize(hosts.size());
        assertThat(seen.values()).containsOnly(EdgeObservation.READY);
    }
}
