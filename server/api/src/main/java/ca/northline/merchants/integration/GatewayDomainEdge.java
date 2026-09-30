package ca.northline.merchants.integration;

import ca.northline.merchants.application.DomainEdge;
import ca.northline.merchants.domain.DomainProblem;
import ca.northline.merchants.domain.EdgeObservation;
import ca.northline.merchants.integration.KubeApi.Kind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code DOMAINS_EDGE_PROVIDER=kubernetes}: the in-cluster reconciler for merchants' own domains (S-31, design in
 * docs/runbooks/custom-domains.md). Everything it writes lives in the app's namespace and carries the label
 * {@value #LABEL}{@code =true}:
 *
 * <ul>
 *   <li><b>Shard Gateways</b> {@code <prefix>-0, -1, …}: one HTTPS listener per domain, at most
 *       {@code listenersPerGateway} (Gateway API's limit is 64). Same class as the environment's Gateway, whose
 *       Envoy proxies merge every Gateway of the class ({@code EnvoyProxy.spec.mergeGateways}), so all shards share the
 *       one load balancer {@code pages.<zone>} resolves to. Port 80 (redirect, HTTP-01 challenges) stays on the main
 *       Gateway, whose HTTP listener has no hostname.
 *   <li>per domain a cert-manager <b>Certificate</b> {@code nl-cd-<hash>} (HTTP-01 through the custom-domain Issuer:
 *       Let's Encrypt staging outside prod) and an <b>HTTPRoute</b> to the consumer app with the edge's headers.
 * </ul>
 *
 * A domain keeps its shard while it is served; freed listeners are reused, empty shards deleted. New domains are
 * added only while the caller's allowance (certificates per hour) and {@code maxDomains} last. Objects are applied
 * only when their spec changed (a hash annotation), so a quiet reconcile is three list calls.
 */
@Slf4j
final class GatewayDomainEdge implements DomainEdge {

    static final String LABEL = "northline.ca/custom-domain";
    static final String SELECTOR = LABEL + "=true";
    static final String HOST = "northline.ca/host";
    static final String SPEC_HASH = "northline.ca/spec-hash";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param responseHeaders set on every response of a custom domain (HSTS without includeSubDomains, nosniff, …)
     */
    record Settings(
            String gatewayClass,
            String gatewayPrefix,
            int listenersPerGateway,
            int maxDomains,
            String issuer,
            String issuerKind,
            String service,
            int servicePort,
            String renewBefore,
            Map<String, String> responseHeaders) {
        Settings {
            responseHeaders = Map.copyOf(responseHeaders);
            if (listenersPerGateway < 1 || listenersPerGateway > 64) {
                throw new IllegalArgumentException("listenersPerGateway must be 1–64 (Gateway API limit)");
            }
        }
    }

    private final KubeApi kube;
    private final Settings settings;

    GatewayDomainEdge(KubeApi kube, Settings settings) {
        this.kube = kube;
        this.settings = settings;
    }

    @Override
    public Map<String, EdgeObservation> reconcile(Set<String> wanted, int newAllowance) {
        var shards = new TreeMap<Integer, Map<String, String>>(); // index → listener name → host
        var programmed = new HashMap<String, Boolean>(); // listener name → Programmed condition
        for (var gateway : kube.list(Kind.GATEWAY, SELECTOR)) {
            var index = shardIndex(gateway.path("metadata").path("name").asString());
            if (index < 0) {
                continue;
            }
            var listeners = new TreeMap<String, String>();
            for (var listener : gateway.path("spec").path("listeners")) {
                listeners.put(
                        listener.path("name").asString(),
                        listener.path("hostname").asString());
            }
            shards.put(index, listeners);
            for (var status : gateway.path("status").path("listeners")) {
                programmed.put(status.path("name").asString(), condition(status, "Programmed", "True"));
            }
        }
        var certificates = byHost(Kind.CERTIFICATE);
        var routes = byHost(Kind.HTTP_ROUTE);
        var placed = new HashMap<String, Integer>(); // host → shard
        shards.forEach((index, listeners) -> listeners.values().forEach(host -> placed.put(host, index)));

        var seen = new HashMap<String, EdgeObservation>();
        var dirty = new TreeSet<Integer>();
        var present = new HashSet<String>(placed.keySet());
        present.addAll(certificates.keySet());
        present.addAll(routes.keySet());
        for (var host : present) {
            if (wanted.contains(host)) {
                continue;
            }
            var index = placed.remove(host);
            if (index != null) {
                Objects.requireNonNull(shards.get(index)).remove(listenerName(host));
                dirty.add(index);
            }
            if (routes.containsKey(host)) {
                kube.delete(Kind.HTTP_ROUTE, objectName(host));
            }
            if (certificates.containsKey(host)) {
                kube.delete(Kind.CERTIFICATE, objectName(host)); // cert-manager's owner reference removes its Secret
            }
            seen.put(host, EdgeObservation.ABSENT);
        }

        var added = new HashSet<String>();
        var allowance = newAllowance;
        for (var host : new TreeSet<>(wanted)) {
            if (placed.containsKey(host)) {
                continue;
            }
            if (placed.size() >= settings.maxDomains()) {
                seen.put(host, new EdgeObservation.Deferred(DomainProblem.CAPACITY));
                continue;
            }
            if (allowance <= 0) {
                seen.put(host, new EdgeObservation.Deferred(DomainProblem.RATE_LIMITED));
                continue;
            }
            var index = freeShard(shards);
            shards.computeIfAbsent(index, _ -> new TreeMap<>()).put(listenerName(host), host);
            placed.put(host, index);
            dirty.add(index);
            added.add(host);
            allowance--;
        }

        for (var index : dirty) {
            var listeners = Objects.requireNonNull(shards.get(index));
            if (listeners.isEmpty()) {
                kube.delete(Kind.GATEWAY, shardName(index));
                shards.remove(index);
            } else {
                kube.apply(Kind.GATEWAY, gateway(index, listeners.values()));
            }
        }

        for (var entry : placed.entrySet()) {
            var host = entry.getKey();
            ensure(Kind.CERTIFICATE, certificate(host), certificates.get(host));
            ensure(Kind.HTTP_ROUTE, route(host, entry.getValue()), routes.get(host));
            if (added.contains(host)) {
                seen.put(host, new EdgeObservation.Provisioning(true));
            } else {
                seen.put(host, observe(certificates.get(host), programmed.get(listenerName(host))));
            }
        }
        if (!added.isEmpty() || !dirty.isEmpty()) {
            log.info(
                    "Custom domains on the edge: {} ({} added, {} shard(s) changed)",
                    placed.size(),
                    added.size(),
                    dirty.size());
        }
        return seen;
    }

    /** What a Certificate and its listener say: ready, failed (cert-manager gave up for now) or still issuing. */
    static EdgeObservation observe(@Nullable JsonNode certificate, @Nullable Boolean listenerProgrammed) {
        if (certificate == null) {
            return new EdgeObservation.Provisioning(false);
        }
        var status = certificate.path("status");
        if (condition(status, "Ready", "True")) {
            return Boolean.TRUE.equals(listenerProgrammed)
                    ? EdgeObservation.READY
                    : new EdgeObservation.Provisioning(false);
        }
        var failedAt = status.path("lastFailureTime");
        if (!failedAt.isMissingNode() && !failedAt.isNull() && !condition(status, "Issuing", "True")) {
            var message = message(status, "Issuing");
            return new EdgeObservation.Failed(message.isEmpty() ? message(status, "Ready") : message);
        }
        return new EdgeObservation.Provisioning(false);
    }

    /** Applies {@code desired} unless the live object already carries its spec hash. */
    private void ensure(Kind kind, ObjectNode desired, @Nullable JsonNode live) {
        var hash = desired.path("metadata").path("annotations").path(SPEC_HASH).asString();
        if (live != null
                && hash.equals(live.path("metadata")
                        .path("annotations")
                        .path(SPEC_HASH)
                        .asString())) {
            return;
        }
        kube.apply(kind, desired);
    }

    private Map<String, JsonNode> byHost(Kind kind) {
        var out = new HashMap<String, JsonNode>();
        for (var item : kube.list(kind, SELECTOR)) {
            var host = item.path("metadata").path("annotations").path(HOST).asString();
            if (!host.isEmpty()) {
                out.put(host, item);
            }
        }
        return out;
    }

    private int freeShard(SortedMap<Integer, Map<String, String>> shards) {
        for (var entry : shards.entrySet()) {
            if (entry.getValue().size() < settings.listenersPerGateway()) {
                return entry.getKey();
            }
        }
        var index = 0;
        while (shards.containsKey(index)) {
            index++;
        }
        return index;
    }

    // ── the objects ──────────────────────────────────────────────────────────────────────────────────────────────

    ObjectNode gateway(int index, Iterable<String> hosts) {
        var gateway = object(Kind.GATEWAY, shardName(index), null);
        var spec = gateway.putObject("spec");
        spec.put("gatewayClassName", settings.gatewayClass());
        var listeners = spec.putArray("listeners");
        for (var host : new TreeSet<>(toSet(hosts))) {
            var listener = listeners.addObject();
            listener.put("name", listenerName(host));
            listener.put("hostname", host);
            listener.put("protocol", "HTTPS");
            listener.put("port", 443);
            var tls = listener.putObject("tls");
            tls.put("mode", "Terminate");
            var ref = tls.putArray("certificateRefs").addObject();
            ref.put("kind", "Secret");
            ref.put("name", secretName(host));
            listener.putObject("allowedRoutes").putObject("namespaces").put("from", "Same");
        }
        return hashed(gateway);
    }

    ObjectNode certificate(String host) {
        var certificate = object(Kind.CERTIFICATE, objectName(host), host);
        var spec = certificate.putObject("spec");
        spec.put("secretName", secretName(host));
        spec.putArray("dnsNames").add(host);
        var issuer = spec.putObject("issuerRef");
        issuer.put("name", settings.issuer());
        issuer.put("kind", settings.issuerKind());
        issuer.put("group", "cert-manager.io");
        var key = spec.putObject("privateKey");
        key.put("algorithm", "ECDSA");
        key.put("size", 256);
        key.put("rotationPolicy", "Always");
        spec.put("renewBefore", settings.renewBefore());
        return hashed(certificate);
    }

    ObjectNode route(String host, int shard) {
        var route = object(Kind.HTTP_ROUTE, objectName(host), host);
        // A merchant's own domain: its DNS is theirs, external-dns leaves it alone.
        annotations(route).put("external-dns.alpha.kubernetes.io/controller", "none");
        var spec = route.putObject("spec");
        var parent = spec.putArray("parentRefs").addObject();
        parent.put("name", shardName(shard));
        parent.put("sectionName", listenerName(host));
        spec.putArray("hostnames").add(host);
        var rule = spec.putArray("rules").addObject();
        rule.putArray("matches")
                .addObject()
                .putObject("path")
                .put("type", "PathPrefix")
                .put("value", "/");
        if (!settings.responseHeaders().isEmpty()) {
            var filter = rule.putArray("filters").addObject();
            filter.put("type", "ResponseHeaderModifier");
            var set = filter.putObject("responseHeaderModifier").putArray("set");
            new TreeMap<>(settings.responseHeaders())
                    .forEach((name, value) -> set.addObject().put("name", name).put("value", value));
        }
        var backend = rule.putArray("backendRefs").addObject();
        backend.put("name", settings.service());
        backend.put("port", settings.servicePort());
        return hashed(route);
    }

    private ObjectNode object(Kind kind, String name, @Nullable String host) {
        var object = JSON.createObjectNode();
        object.put("apiVersion", kind.apiVersion());
        object.put("kind", kind.kind);
        var metadata = object.putObject("metadata");
        metadata.put("name", name);
        var labels = metadata.putObject("labels");
        labels.put(LABEL, "true");
        labels.put("app.kubernetes.io/part-of", "northline");
        labels.put("app.kubernetes.io/managed-by", "northline-api");
        var annotations = metadata.putObject("annotations");
        if (host != null) {
            annotations.put(HOST, host);
        }
        return object;
    }

    /** Adds the spec hash annotation (computed over everything else). */
    private static ObjectNode hashed(ObjectNode object) {
        var hash = sha256(JSON.writeValueAsString(object)).substring(0, 16);
        annotations(object).put(SPEC_HASH, hash);
        return object;
    }

    private static ObjectNode annotations(ObjectNode object) {
        return (ObjectNode) object.get("metadata").get("annotations");
    }

    String shardName(int index) {
        return settings.gatewayPrefix() + "-" + index;
    }

    private int shardIndex(String name) {
        var prefix = settings.gatewayPrefix() + "-";
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /** {@code nl-cd-<first 20 hex of sha256(host)>}: stable, DNS-label safe, independent of the host's length. */
    static String objectName(String host) {
        return "nl-cd-" + sha256(host).substring(0, 20);
    }

    static String secretName(String host) {
        return objectName(host) + "-tls";
    }

    static String listenerName(String host) {
        return "cd-" + sha256(host).substring(0, 20);
    }

    private static boolean condition(JsonNode status, String type, String value) {
        for (var condition : status.path("conditions")) {
            if (type.equals(condition.path("type").asString())) {
                return value.equals(condition.path("status").asString());
            }
        }
        return false;
    }

    private static String message(JsonNode status, String type) {
        for (var condition : status.path("conditions")) {
            if (type.equals(condition.path("type").asString())) {
                return condition.path("message").asString();
            }
        }
        return "";
    }

    private static Set<String> toSet(Iterable<String> values) {
        var out = new HashSet<String>();
        values.forEach(out::add);
        return out;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
