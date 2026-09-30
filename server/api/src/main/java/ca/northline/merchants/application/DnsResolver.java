package ca.northline.merchants.application;

import java.util.List;

/**
 * Outbound port: asks DNS (S-31 custom domains). Adapters chosen by {@code northline.domains.dns.provider}
 * ({@code DOMAINS_DNS_PROVIDER}): {@code local} = an in-memory zone (local/test), {@code doh} = DNS over HTTPS (RFC 8484)
 * against a public resolver, {@code jndi} = the JDK's DNS client against given (or the system's) name servers.
 */
public interface DnsResolver {

    enum Type {
        A,
        AAAA,
        CNAME,
        TXT
    }

    /**
     * Records of {@code type} at a name. A/AAAA follow CNAMEs (the addresses the name finally resolves to); CNAME is the
     * one record at exactly that name; TXT values have their character-strings joined.
     *
     * @param failed the resolver could not answer (SERVFAIL, timeout, network): nothing is known
     * @param values lower-case names without the trailing dot, addresses, or TXT texts; empty = no such record
     *     (NOERROR/NODATA or NXDOMAIN)
     */
    record Answer(boolean failed, List<String> values) {
        public static final Answer NONE = new Answer(false, List.of());
        public static final Answer ERROR = new Answer(true, List.of());

        public Answer {
            values = List.copyOf(values);
        }

        public static Answer of(List<String> values) {
            return new Answer(false, values);
        }
    }

    Answer lookup(String name, Type type);
}
