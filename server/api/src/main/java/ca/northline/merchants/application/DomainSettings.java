package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DomainPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Custom-domain settings ({@code northline.domains.*}, docs/runbooks/custom-domains.md).
 *
 * @param targetHost where merchants point their CNAME: {@code pages.<zone>} ({@code DOMAINS_TARGET_HOST})
 * @param edgeAddresses fixed public addresses of the edge for apex A records ({@code DOMAINS_EDGE_ADDRESSES}); the
 *     addresses {@code targetHost} resolves to are accepted too
 * @param blockedSuffixes zones no merchant may claim ({@code DOMAINS_BLOCKED_SUFFIXES}); our own zone is always blocked
 * @param issuePerHour certificates requested per hour at most, across all merchants ({@code DOMAINS_ISSUE_PER_HOUR})
 * @param requestCooldown a page asks for a new certificate at most this often ({@code DOMAINS_REQUEST_COOLDOWN})
 * @param checkCooldown "Check now" re-asks DNS at most this often per domain ({@code DOMAINS_CHECK_COOLDOWN})
 * @param checkBatch domains checked per scheduler run
 */
public record DomainSettings(
        String targetHost,
        List<String> edgeAddresses,
        List<String> blockedSuffixes,
        DomainPolicy policy,
        int issuePerHour,
        Duration requestCooldown,
        Duration checkCooldown,
        int checkBatch) {

    public DomainSettings {
        targetHost = normalize(targetHost);
        edgeAddresses = edgeAddresses.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        var zone = targetHost.substring(targetHost.indexOf('.') + 1);
        blockedSuffixes = java.util.stream.Stream.concat(blockedSuffixes.stream(), java.util.stream.Stream.of(zone))
                .map(DomainSettings::normalize)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** A zone we operate (or one configured as off limits) contains this domain. */
    public boolean blocked(CustomDomain domain) {
        return blockedSuffixes.stream().anyMatch(zone -> CustomDomain.within(domain.value(), zone));
    }

    static String normalize(String name) {
        var n = name.strip().toLowerCase(Locale.ROOT);
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }
}
