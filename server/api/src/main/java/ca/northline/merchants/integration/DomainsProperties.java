package ca.northline.merchants.integration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.domains.*} — storefront custom domains (S-31, docs/runbooks/custom-domains.md).
 *
 * @param targetHost where merchants point their CNAME: {@code pages.<zone>} ({@code DOMAINS_TARGET_HOST}; the chart sets it
 *     from {@code urls.pages})
 * @param edgeAddresses fixed public addresses of the edge for apex A/AAAA records ({@code DOMAINS_EDGE_ADDRESSES})
 * @param blockedSuffixes zones no merchant may claim besides our own ({@code DOMAINS_BLOCKED_SUFFIXES})
 * @param issuePerHour certificates requested per hour at most ({@code DOMAINS_ISSUE_PER_HOUR})
 * @param requestCooldown one certificate request per page per this long ({@code DOMAINS_REQUEST_COOLDOWN})
 * @param checkCooldown "Check now" asks DNS at most this often per domain ({@code DOMAINS_CHECK_COOLDOWN})
 * @param checkInterval how often the scheduler looks for due DNS checks ({@code DOMAINS_CHECK_INTERVAL})
 * @param edgeInterval how often the edge is reconciled ({@code DOMAINS_EDGE_INTERVAL})
 */
@ConfigurationProperties("northline.domains")
record DomainsProperties(
        @DefaultValue("pages.northline.ca") String targetHost,
        @DefaultValue List<String> edgeAddresses,
        @DefaultValue List<String> blockedSuffixes,
        @DefaultValue("20") int issuePerHour,
        @DefaultValue("PT1H") Duration requestCooldown,
        @DefaultValue("PT15S") Duration checkCooldown,
        @DefaultValue("25") int checkBatch,
        @DefaultValue("PT1M") Duration checkInterval,
        @DefaultValue("PT1M") Duration edgeInterval,
        @DefaultValue Timing timing,
        @DefaultValue Dns dns,
        @DefaultValue Edge edge) {

    /** The lifecycle's timing ({@code DOMAINS_VERIFY_WINDOW}, {@code DOMAINS_GRACE_PERIOD}, …). */
    record Timing(
            @DefaultValue("P7D") Duration verifyWindow,
            @DefaultValue("PT6H") Duration recheck,
            @DefaultValue("PT72H") Duration grace,
            @DefaultValue("PT30M") Duration lostRecheck,
            @DefaultValue("PT6H") Duration failedRecheck,
            @DefaultValue("3") int maxFailures) {}

    /**
     * @param provider {@code local} (in-memory zone; refused under staging/prod) · {@code doh} (DNS over HTTPS, RFC 8484)
     *     · {@code jndi} (the JDK's DNS client) — {@code DOMAINS_DNS_PROVIDER}
     * @param dohUrl the DoH endpoint ({@code DOMAINS_DOH_URL}); CIRA Canadian Shield (Private: no filtering) by default
     * @param servers jndi: name servers {@code host[:port]} ({@code DOMAINS_DNS_SERVERS}); empty = the system's
     * @param timeout per query
     */
    record Dns(
            @DefaultValue("local") String provider,

            @DefaultValue("https://private.canadianshield.cira.ca/dns-query")
            URI dohUrl,

            @DefaultValue List<String> servers,
            @DefaultValue("PT3S") Duration timeout) {

        String effectiveProvider() {
            return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The edge that serves custom domains ({@code DOMAINS_EDGE_*}; the chart sets them with
     * {@code edge.domainReconciler.enabled}).
     *
     * @param provider {@code local} (in memory, ready at once; refused under staging/prod) · {@code kubernetes}
     * @param namespace the app's namespace; the reconciler writes nowhere else
     * @param gatewayClass class of the shard Gateways — the same as the environment's Gateway, so Envoy Gateway merges
     *     them onto the one load balancer {@code pages.<zone>} points at
     * @param gatewayPrefix shard Gateways are {@code <prefix>-0}, {@code -1}, …
     * @param listenersPerGateway Gateway API allows 64 listeners per Gateway
     * @param maxDomains custom domains served at most (capacity; beyond it domains wait as {@code capacity})
     * @param issuer cert-manager issuer for custom domains (Let's Encrypt staging outside prod)
     * @param service / servicePort the consumer app the routes send traffic to
     * @param responseHeaders JSON object of response headers set on every custom-domain route; blank =
     *     {@link DomainsConfig#DEFAULT_HEADERS} (HSTS without includeSubDomains: a merchant's other subdomains are none of
     *     our business)
     * @param apiUrl the Kubernetes API; {@code token}/{@code caCert}: the pod's projected ServiceAccount token and CA
     */
    record Edge(
            @DefaultValue("local") String provider,
            @DefaultValue("northline") String namespace,
            @DefaultValue("envoy") String gatewayClass,
            @DefaultValue("northline-custom") String gatewayPrefix,
            @DefaultValue("64") int listenersPerGateway,
            @DefaultValue("1000") int maxDomains,
            @DefaultValue("northline-acme-custom") String issuer,
            @DefaultValue("Issuer") String issuerKind,
            @DefaultValue("northline-consumer") String service,
            @DefaultValue("3000") int servicePort,
            @DefaultValue("720h") String renewBefore,
            @DefaultValue("") String responseHeaders,
            @DefaultValue("https://kubernetes.default.svc") URI apiUrl,

            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token")
            Path token,

            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            Path caCert) {

        String effectiveProvider() {
            return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
        }
    }
}
