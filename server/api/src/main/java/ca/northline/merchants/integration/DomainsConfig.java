package ca.northline.merchants.integration;

import ca.northline.merchants.application.DnsResolver;
import ca.northline.merchants.application.DomainEdge;
import ca.northline.merchants.application.DomainSettings;
import ca.northline.merchants.domain.DomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom domains (S-31): the DNS resolver ({@code DOMAINS_DNS_PROVIDER}: {@code local} | {@code doh} | {@code jndi}) and
 * the edge ({@code DOMAINS_EDGE_PROVIDER}: {@code local} | {@code kubernetes}). {@code local} is refused under
 * staging/prod for both.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DomainsProperties.class)
class DomainsConfig {

    @Bean
    DomainSettings domainSettings(DomainsProperties p) {
        var t = p.timing();
        return new DomainSettings(
                p.targetHost(),
                p.edgeAddresses(),
                p.blockedSuffixes(),
                new DomainPolicy(
                        t.verifyWindow(), t.recheck(), t.grace(), t.lostRecheck(), t.failedRecheck(), t.maxFailures()),
                p.issuePerHour(),
                p.requestCooldown(),
                p.checkCooldown(),
                p.checkBatch());
    }

    @Bean
    DnsResolver dnsResolver(DomainsProperties p, DomainSettings settings, Environment env) {
        var dns = p.dns();
        return switch (dns.effectiveProvider()) {
            case "local" -> {
                refuseInCloud(env, "DOMAINS_DNS_PROVIDER=local", "doh or jndi");
                yield new FakeDnsResolver(settings.targetHost());
            }
            case "doh" -> {
                log.info("Custom domains: DNS over HTTPS at {}", dns.dohUrl());
                yield new DohDnsResolver(dns.dohUrl(), dns.timeout());
            }
            case "jndi" -> {
                log.info(
                        "Custom domains: DNS through JNDI, servers {}",
                        dns.servers().isEmpty() ? "of the system" : dns.servers());
                yield new JndiDnsResolver(dns.servers(), dns.timeout());
            }
            default ->
                throw new IllegalStateException(
                        "DOMAINS_DNS_PROVIDER must be local, doh or jndi, not " + dns.provider());
        };
    }

    @Bean
    DomainEdge domainEdge(DomainsProperties p, Environment env) {
        var edge = p.edge();
        return switch (edge.effectiveProvider()) {
            case "local" -> {
                refuseInCloud(
                        env, "DOMAINS_EDGE_PROVIDER=local", "kubernetes (edge.domainReconciler.enabled in the chart)");
                yield new LocalDomainEdge();
            }
            case "kubernetes" -> {
                log.info(
                        "Custom domains: Gateway API edge in namespace {} (class {}, issuer {})",
                        edge.namespace(),
                        edge.gatewayClass(),
                        edge.issuer());
                yield new GatewayDomainEdge(
                        new HttpKubeApi(edge.apiUrl(), edge.namespace(), edge.token(), edge.caCert()),
                        new GatewayDomainEdge.Settings(
                                edge.gatewayClass(),
                                edge.gatewayPrefix(),
                                edge.listenersPerGateway(),
                                edge.maxDomains(),
                                edge.issuer(),
                                edge.issuerKind(),
                                edge.service(),
                                edge.servicePort(),
                                edge.renewBefore(),
                                headers(edge.responseHeaders())));
            }
            default ->
                throw new IllegalStateException(
                        "DOMAINS_EDGE_PROVIDER must be local or kubernetes, not " + edge.provider());
        };
    }

    /** The chart's edge headers, minus {@code includeSubDomains}: a merchant's other subdomains are theirs. */
    static final Map<String, String> DEFAULT_HEADERS = Map.of(
            "Strict-Transport-Security", "max-age=63072000",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "strict-origin-when-cross-origin");

    static Map<String, String> headers(String json) {
        if (json.isBlank()) {
            return DEFAULT_HEADERS;
        }
        return JsonMapper.builder().build().readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
    }

    private static void refuseInCloud(Environment env, String setting, String instead) {
        if (env.matchesProfiles("staging | prod")) {
            throw new IllegalStateException(setting + " is not allowed under staging/prod: use " + instead
                    + " (docs/runbooks/custom-domains.md)");
        }
    }
}
