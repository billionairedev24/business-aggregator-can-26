package ca.northline.merchants.integration;

import ca.northline.merchants.application.DnsResolver;
import ca.northline.merchants.application.DnsSandbox;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOCAL / TEST ONLY ({@code DOMAINS_DNS_PROVIDER=local}): an in-memory zone. Empty except for {@code pages.<zone>},
 * which resolves to {@value #EDGE_ADDRESS} (TEST-NET-1) so apex A records can point at it. Records come from the
 * Studio's "Simulate DNS records →" ({@link DnsSandbox}) or from tests; A/AAAA follow CNAMEs like a recursive resolver.
 */
public final class FakeDnsResolver implements DnsResolver, DnsSandbox {

    public static final String EDGE_ADDRESS = "192.0.2.10";

    private record Key(String name, DnsResolver.Type type) {}

    private final String targetHost;
    private final Map<Key, List<String>> records = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    public FakeDnsResolver(String targetHost) {
        this.targetHost = normalize(targetHost);
        reset();
    }

    @Override
    public Answer lookup(String name, DnsResolver.Type type) {
        var n = normalize(name);
        if (failing.contains(n)) {
            return Answer.ERROR;
        }
        var values = records.get(new Key(n, type));
        if (values != null) {
            return Answer.of(values);
        }
        if (type == DnsResolver.Type.A || type == DnsResolver.Type.AAAA) {
            var cname = records.get(new Key(n, DnsResolver.Type.CNAME));
            if (cname != null && !cname.isEmpty()) {
                return lookup(cname.getFirst(), type);
            }
        }
        return Answer.NONE;
    }

    @Override
    public void publish(String name, DnsResolver.Type type, List<String> values) {
        records.put(
                new Key(normalize(name), type),
                values.stream()
                        .map(v -> type == DnsResolver.Type.TXT ? v : normalize(v))
                        .toList());
    }

    /** Removes every record of a name. */
    public void remove(String name) {
        var n = normalize(name);
        records.keySet().removeIf(k -> k.name().equals(n));
    }

    /** Makes the resolver fail for a name (SERVFAIL / timeout) until {@link #recover}. */
    public void fail(String name) {
        failing.add(normalize(name));
    }

    public void recover(String name) {
        failing.remove(normalize(name));
    }

    /** Back to the initial zone. */
    public void reset() {
        records.clear();
        failing.clear();
        records.put(new Key(targetHost, DnsResolver.Type.A), List.of(EDGE_ADDRESS));
    }

    private static String normalize(String name) {
        var n = name.strip().toLowerCase(Locale.ROOT);
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }
}
