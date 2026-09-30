package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DnsFindings;
import ca.northline.merchants.domain.DnsFindings.Pointing;
import ca.northline.merchants.domain.DnsFindings.Txt;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads what DNS says about a claimed domain (S-31): the ownership TXT record at {@code _northline-verify.<domain>}
 * and whether the name reaches the edge — its CNAME chain passes through {@code pages.<zone>}, or (apex: ALIAS, ANAME,
 * CNAME flattening, A records) every address it resolves to is one of the edge's.
 */
@Component
@RequiredArgsConstructor
class DnsInspector {

    /** CNAME hops followed before giving up (resolvers stop at about as many). */
    static final int MAX_HOPS = 8;

    private final DnsResolver dns;
    private final DomainSettings settings;

    DnsFindings inspect(CustomDomain domain, String token) {
        return new DnsFindings(txt(domain, token), pointing(domain.value()));
    }

    /** Only the ownership record: is the claim's token there? */
    Txt txt(CustomDomain domain, String token) {
        var answer = dns.lookup(domain.verificationName(), DnsResolver.Type.TXT);
        if (answer.failed()) {
            return Txt.UNKNOWN;
        }
        if (answer.values().isEmpty()) {
            return Txt.MISSING;
        }
        return answer.values().stream().map(String::strip).anyMatch(token::equals) ? Txt.FOUND : Txt.MISMATCH;
    }

    private Pointing pointing(String host) {
        var target = settings.targetHost();
        var name = host;
        var failed = false;
        var aliased = false;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            var cname = dns.lookup(name, DnsResolver.Type.CNAME);
            if (cname.failed()) {
                failed = true;
                break;
            }
            if (cname.values().isEmpty()) {
                break;
            }
            name = DomainSettings.normalize(cname.values().getFirst());
            aliased = true;
            if (name.equals(target)) {
                return Pointing.CNAME;
            }
        }
        var addresses = addresses(host);
        if (addresses == null) {
            return Pointing.UNKNOWN;
        }
        if (addresses.isEmpty()) {
            return failed ? Pointing.UNKNOWN : aliased ? Pointing.ELSEWHERE : Pointing.MISSING;
        }
        var ours = new HashSet<String>();
        settings.edgeAddresses().forEach(a -> ours.add(address(a)));
        var targetAddresses = addresses(target);
        if (targetAddresses != null) {
            ours.addAll(targetAddresses);
        }
        if (ours.containsAll(addresses)) {
            return Pointing.ADDRESS;
        }
        return targetAddresses == null && settings.edgeAddresses().isEmpty() ? Pointing.UNKNOWN : Pointing.ELSEWHERE;
    }

    /** A and AAAA addresses of a name; null when the resolver failed for both. */
    private @Nullable Set<String> addresses(String name) {
        var v4 = dns.lookup(name, DnsResolver.Type.A);
        var v6 = dns.lookup(name, DnsResolver.Type.AAAA);
        if (v4.failed() && v6.failed()) {
            return null;
        }
        var all = new HashSet<String>();
        v4.values().forEach(a -> all.add(address(a)));
        v6.values().forEach(a -> all.add(address(a)));
        return all;
    }

    /** One spelling per address ({@code 2001:db8::1} = {@code 2001:db8:0:0:0:0:0:1}). */
    static String address(String literal) {
        try {
            return InetAddress.ofLiteral(literal.strip()).getHostAddress();
        } catch (IllegalArgumentException _) {
            return literal.strip();
        }
    }
}
