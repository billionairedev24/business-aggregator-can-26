package ca.northline.merchants.application;

import java.util.List;

/**
 * LOCAL ONLY: the in-memory zone behind {@code DOMAINS_DNS_PROVIDER=local}, so the Studio's "Simulate DNS records →"
 * (dev builds) can publish a domain's records without a real DNS host. No bean exists with a real resolver.
 */
public interface DnsSandbox {
    void publish(String name, DnsResolver.Type type, List<String> values);
}
