package ca.northline.merchants.integration;

import ca.northline.merchants.application.DnsResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@code DOMAINS_DNS_PROVIDER=jndi}: the JDK's own DNS client ({@code com.sun.jndi.dns}) over UDP/TCP 53, against the
 * given name servers ({@code DOMAINS_DNS_SERVERS}, e.g. {@code 149.112.121.20,149.112.122.20}) or the system's
 * resolvers. No HTTP and no library; needs port 53 out (or an in-cluster resolver that recurses). JNDI does not follow
 * CNAMEs for A/AAAA, so this does, like a stub resolver.
 */
@Slf4j
final class JndiDnsResolver implements DnsResolver {

    @SuppressWarnings("JdkObsolete") // InitialDirContext takes a Hashtable
    private final Hashtable<String, String> environment = new Hashtable<>();

    JndiDnsResolver(List<String> servers, Duration timeout) {
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put(
                "java.naming.provider.url",
                servers.isEmpty()
                        ? "dns:"
                        : servers.stream()
                                .map(String::strip)
                                .map(s -> "dns://" + s)
                                .collect(Collectors.joining(" ")));
        environment.put("com.sun.jndi.dns.timeout.initial", Long.toString(Math.max(100, timeout.toMillis())));
        environment.put("com.sun.jndi.dns.timeout.retries", "2");
    }

    @Override
    public Answer lookup(String name, DnsResolver.Type type) {
        return lookup(name, type, 0);
    }

    /**
     * {@code BanJNDI} guards against JNDI lookups of attacker-chosen URLs (LDAP/RMI object deserialization). Here the
     * context is always the DNS provider ({@code dns:} URLs only, fixed in the environment), the name is a validated host
     * name, and DNS attributes are plain strings — nothing is ever deserialized.
     */
    @SuppressWarnings("BanJNDI")
    private Answer lookup(String name, DnsResolver.Type type, int hops) {
        var follows = (type == DnsResolver.Type.A || type == DnsResolver.Type.AAAA) && hops < 8;
        try {
            var context = new InitialDirContext(environment);
            try {
                // one type per query: several would make JNDI ask for ANY, which resolvers answer minimally (RFC 8482)
                // a plain name (not a dns: URL), so the servers of java.naming.provider.url answer
                var dnsName = absolute(name);
                var values = values(
                        context.getAttributes(dnsName, new String[] {type.name()})
                                .get(type.name()),
                        type);
                if (values.isEmpty() && follows) {
                    var cname = values(
                            context.getAttributes(dnsName, new String[] {"CNAME"})
                                    .get("CNAME"),
                            DnsResolver.Type.CNAME);
                    if (!cname.isEmpty()) {
                        return lookup(cname.getFirst(), type, hops + 1);
                    }
                }
                return Answer.of(values);
            } finally {
                context.close();
            }
        } catch (NameNotFoundException _) {
            return Answer.NONE;
        } catch (NamingException e) {
            log.debug("DNS {} {} failed: {}", name, type, e.toString());
            return Answer.ERROR;
        }
    }

    private static String absolute(String name) {
        return name.endsWith(".") ? name : name + ".";
    }

    private static List<String> values(@Nullable Attribute attribute, DnsResolver.Type type) throws NamingException {
        var out = new ArrayList<String>();
        if (attribute == null) {
            return out;
        }
        for (int i = 0; i < attribute.size(); i++) {
            var raw = String.valueOf(attribute.get(i));
            out.add(
                    switch (type) {
                        case TXT -> text(raw);
                        case CNAME -> {
                            var n = raw.toLowerCase(Locale.ROOT);
                            yield n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
                        }
                        case A, AAAA -> raw.strip();
                    });
        }
        return out;
    }

    /** JNDI renders a TXT record's strings space-separated, quoting those with blanks: join them back. */
    static String text(String raw) {
        var s = raw.strip();
        if (!s.startsWith("\"")) {
            return String.join("", s.split(" "));
        }
        var out = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
            } else if (quoted || c != ' ') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
