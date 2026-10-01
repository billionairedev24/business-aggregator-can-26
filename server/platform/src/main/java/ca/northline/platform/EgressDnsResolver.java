package ca.northline.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Apache HttpClient 5 resolver that applies the {@link EgressPolicy} to every address of a host and returns exactly the
 * checked addresses, which the connection then uses (no second lookup: DNS rebinding can't swap in a refused one).
 */
public record EgressDnsResolver(EgressPolicy policy, HostResolver resolver) implements DnsResolver {

    /** A host was refused: one of its addresses is not a public unicast address. */
    public static final class Refused extends UnknownHostException {
        private static final long serialVersionUID = 1L;

        public Refused(String reason) {
            super(reason);
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        var addresses = resolver.resolve(host);
        var refused = policy.refuseAddresses(host, addresses);
        if (refused.isPresent()) {
            throw new Refused(refused.get());
        }
        return addresses.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host; // never used for the connection; no reverse lookups
    }
}
