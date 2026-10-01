package ca.northline.developer.domain;

import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validation rules and messages for Settings › API &amp; integrations (validation-rules.md has no section for them;
 * docs/DECISIONS.md "Settings &amp; compliance"). The client ({@code features/settings/validation.ts}) uses the same text.
 */
public final class DeveloperRules {
    private DeveloperRules() {}

    public static final int NAME_MAX = 60;
    public static final String NAME_REQUIRED = "Name the key so you can tell it apart.";
    public static final String NAME_TOO_LONG = "At most 60 characters.";
    public static final String SCOPES_REQUIRED = "Pick at least one scope.";
    public static final String SCOPE_UNKNOWN = "Pick scopes from the list.";
    public static final String URL_REQUIRED = "Enter the URL that receives events.";
    public static final String URL_HTTPS = "Enter an https:// URL.";
    public static final String EVENTS_REQUIRED = "Pick at least one event.";
    public static final String EVENT_UNKNOWN = "Pick events from the list.";
    public static final int OVERLAP_MAX_HOURS = 168;
    public static final int OVERLAP_DEFAULT_HOURS = 24;
    public static final String OVERLAP_RANGE = "Keep the old secret for 0 to 168 hours.";
    public static final int ORIGINS_MAX = 10;
    public static final String ORIGIN_FORMAT = "Enter a site address like https://www.example.com.";
    public static final String ORIGINS_TOO_MANY = "Up to 10 sites.";

    /** Scopes a merchant key may carry (design: "storefront:read booking:write", "payouts:read orders:read"). */
    public static final List<String> SCOPES = List.of(
            "storefront:read",
            "listings:read",
            "listings:write",
            "booking:read",
            "booking:write",
            "orders:read",
            "payouts:read",
            "reviews:read");

    /** Events a webhook endpoint may subscribe to (design: booking.confirmed · booking.completed · …). */
    public static final List<String> EVENTS = List.of(
            "booking.confirmed",
            "booking.completed",
            "order.placed",
            "order.delivered",
            "payment.released",
            "refund.issued",
            "review.created");

    public static String keyName(String raw) {
        var name = raw.strip();
        if (name.isEmpty()) {
            throw RuleViolation.of("name", "required", NAME_REQUIRED);
        }
        if (name.length() > NAME_MAX) {
            throw RuleViolation.of("name", "length", NAME_TOO_LONG);
        }
        return name;
    }

    public static List<String> scopes(List<String> raw) {
        return subset("scopes", raw, SCOPES, SCOPES_REQUIRED, SCOPE_UNKNOWN);
    }

    public static List<String> events(List<String> raw) {
        return subset("events", raw, EVENTS, EVENTS_REQUIRED, EVENT_UNKNOWN);
    }

    /**
     * How long the secret being replaced keeps signing next to the new one (S-33): 0 = it stops at once (a leaked
     * secret), default 24 h, at most 7 days.
     */
    public static Duration secretOverlap(@Nullable Integer hours) {
        var value = hours == null ? OVERLAP_DEFAULT_HOURS : hours;
        if (value < 0 || value > OVERLAP_MAX_HOURS) {
            throw RuleViolation.of("overlapHours", "range", OVERLAP_RANGE);
        }
        return Duration.ofHours(value);
    }

    /** https only, except http://localhost for local development. */
    public static String webhookUrl(String raw) {
        var url = raw.strip();
        if (url.isEmpty()) {
            throw RuleViolation.of("url", "required", URL_REQUIRED);
        }
        try {
            var uri = new URI(url);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost();
            var local = "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host));
            if (host == null || !("https".equals(scheme) || local) || url.length() > 500) {
                throw RuleViolation.of("url", "format", URL_HTTPS);
            }
        } catch (URISyntaxException e) {
            throw RuleViolation.of("url", "format", URL_HTTPS);
        }
        return url;
    }

    /**
     * S-76: the sites a publishable key's embed answers on, as browser origins ({@code https://www.example.com}, port
     * kept when not the default). A bare host gets https://; a path, query or credentials are refused; http only for
     * localhost (local development). Duplicates collapse; at most {@value #ORIGINS_MAX}.
     */
    public static List<String> origins(List<String> raw) {
        var out = new java.util.LinkedHashSet<String>();
        for (var entry : raw) {
            var value = entry.strip();
            if (value.isEmpty()) {
                continue;
            }
            out.add(origin(value.contains("://") ? value : "https://" + value));
        }
        if (out.size() > ORIGINS_MAX) {
            throw RuleViolation.of("allowedOrigins", "size", ORIGINS_TOO_MANY);
        }
        return List.copyOf(out);
    }

    private static String origin(String value) {
        try {
            var uri = new URI(value);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            var path = uri.getRawPath() == null ? "" : uri.getRawPath();
            var local = "localhost".equals(host) || "127.0.0.1".equals(host);
            var bare = (path.isEmpty() || "/".equals(path))
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawUserInfo() == null;
            if (host.isEmpty()
                    || !bare
                    || !("https".equals(scheme) || ("http".equals(scheme) && local))
                    || value.length() > 200) {
                throw RuleViolation.of("allowedOrigins", "format", ORIGIN_FORMAT);
            }
            var defaultPort = "https".equals(scheme) ? 443 : 80;
            return scheme + "://" + host
                    + (uri.getPort() == -1 || uri.getPort() == defaultPort ? "" : ":" + uri.getPort());
        } catch (URISyntaxException e) {
            throw RuleViolation.of("allowedOrigins", "format", ORIGIN_FORMAT);
        }
    }

    private static List<String> subset(
            String field, List<String> raw, List<String> allowed, String required, String unknown) {
        var picked = raw.stream().map(String::strip).distinct().toList();
        if (picked.isEmpty()) {
            throw RuleViolation.of(field, "required", required);
        }
        if (!Set.copyOf(allowed).containsAll(picked)) {
            throw RuleViolation.of(field, "allowed", unknown);
        }
        return allowed.stream().filter(picked::contains).toList();
    }
}
