package ca.northline.merchants.integration;

import ca.northline.merchants.application.DnsResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * {@code DOMAINS_DNS_PROVIDER=doh}: DNS over HTTPS (RFC 8484) — the wire-format message POSTed as
 * {@code application/dns-message}, which every DoH server must accept (CIRA Canadian Shield, Cloudflare, Google, Quad9).
 * Nothing cloud-specific: port 443 out is all it needs. NXDOMAIN = no records; SERVFAIL, other codes, HTTP errors and
 * timeouts = {@link Answer#ERROR}.
 */
@Slf4j
final class DohDnsResolver implements DnsResolver {

    static final String MEDIA_TYPE = "application/dns-message";

    interface DohApi {
        @PostExchange(contentType = MEDIA_TYPE, accept = MEDIA_TYPE)
        byte[] query(@RequestBody byte[] message);
    }

    private final DohApi api;

    DohDnsResolver(URI endpoint, Duration timeout) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(timeout);
        var rest = RestClient.builder()
                .baseUrl(endpoint.toString())
                .requestFactory(requests)
                .build();
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(DohApi.class);
    }

    @Override
    public Answer lookup(String name, DnsResolver.Type type) {
        try {
            var response = DnsWire.parse(api.query(DnsWire.query(name, type)));
            return switch (response.rcode()) {
                case DnsWire.NOERROR ->
                    Answer.of(response.answers().stream()
                            .filter(r -> DnsWire.type(r.type()) == type)
                            .map(DnsWire.ResourceRecord::data)
                            .toList());
                case DnsWire.NXDOMAIN -> Answer.NONE;
                default -> {
                    log.debug("DoH {} {}: rcode {}", name, type, response.rcode());
                    yield Answer.ERROR;
                }
            };
        } catch (RuntimeException e) {
            log.debug("DoH {} {} failed: {}", name, type, e.toString());
            return Answer.ERROR;
        }
    }
}
