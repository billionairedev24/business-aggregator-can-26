package ca.northline.search.integration;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * S-119: client addresses the search rate limit never counts ({@code SEARCH_RATE_LIMIT_EXEMPT}, comma-separated
 * addresses or CIDR ranges) — the load generators of a load test, which would otherwise measure 429s instead of
 * search. Empty by default; refused under {@code prod}, where nothing may bypass the limit.
 */
record RateLimitExemptions(List<IpAddressMatcher> ranges) {

    static final RateLimitExemptions NONE = new RateLimitExemptions(List.of());

    RateLimitExemptions {
        ranges = List.copyOf(ranges);
    }

    /** @throws IllegalStateException under {@code prod}, or for something that is neither an address nor a range */
    static RateLimitExemptions of(List<String> entries, Environment environment) {
        var ranges =
                entries.stream().map(String::strip).filter(e -> !e.isEmpty()).toList();
        if (ranges.isEmpty()) {
            return NONE;
        }
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(Set.of("prod")::contains)) {
            throw new IllegalStateException("SEARCH_RATE_LIMIT_EXEMPT is refused under prod: run load tests against "
                    + "staging or local (docs/runbooks/load-testing.md)");
        }
        return new RateLimitExemptions(
                ranges.stream().map(RateLimitExemptions::matcher).toList());
    }

    boolean covers(String address) {
        // an IP literal only: a host name is never looked up, never exempt
        return !ranges.isEmpty() && literal(address) && ranges.stream().anyMatch(range -> range.matches(address));
    }

    private static IpAddressMatcher matcher(String range) {
        var slash = range.indexOf('/');
        if (!literal(slash < 0 ? range : range.substring(0, slash))) {
            throw new IllegalStateException(
                    "SEARCH_RATE_LIMIT_EXEMPT: '" + range + "' is not an IP address or CIDR range");
        }
        try {
            return new IpAddressMatcher(range);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "SEARCH_RATE_LIMIT_EXEMPT: '" + range + "' is not an IP address or CIDR range", e);
        }
    }

    private static boolean literal(String address) {
        try {
            return InetAddress.ofLiteral(address) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
