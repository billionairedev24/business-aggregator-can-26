package ca.northline.auth.clients;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * How strict client registration is, from the active profiles (like the other "refused under staging/prod" rules):
 *
 * <ul>
 *   <li>{@link #LOCAL} ({@code local}, {@code test}): {@code http} redirect URIs and {@code {noop}} secrets allowed.
 *   <li>{@link #DEV} ({@code dev}, or no profile): {@code https}, or {@code http} on a loopback host (the local cloud
 *       rehearsal, RFC 8252 § 7.3); {@code {noop}} secrets are accepted with a warning.
 *   <li>{@link #STRICT} ({@code staging}, {@code prod}): {@code https} only and hashed secrets only.
 * </ul>
 *
 * In every mode a public (mobile) client may also use a private-use URI scheme in reverse-domain form
 * ({@code ca.northline.app:/oauth2redirect}, RFC 8252 § 7.1).
 */
enum ClientPolicy {
    LOCAL,
    DEV,
    STRICT;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    static ClientPolicy of(Environment environment) {
        if (environment.matchesProfiles("staging | prod")) {
            return STRICT;
        }
        return environment.matchesProfiles("local | test") ? LOCAL : DEV;
    }

    /** {@code null} when {@code uri} is acceptable as a redirect for this kind of client, else why not. */
    @Nullable
    String rejectRedirect(String uri, ClientSpec.Type type) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (Exception _) {
            return "is not a valid URI";
        }
        if (!parsed.isAbsolute() || uri.contains("*")) {
            return "must be an absolute URI without wildcards";
        }
        if (parsed.getRawFragment() != null) {
            return "must not have a fragment";
        }
        var scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "https" -> parsed.getHost() == null ? "has no host" : null;
            case "http" ->
                httpAllowed(parsed) ? null : "must use https outside local (http only on loopback under dev)";
            default ->
                type == ClientSpec.Type.PUBLIC && scheme.contains(".")
                        ? null
                        : "must use https (a public client may use a reverse-domain private-use scheme)";
        };
    }

    private boolean httpAllowed(URI uri) {
        return switch (this) {
            case LOCAL -> true;
            case DEV -> uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
            case STRICT -> false;
        };
    }

    /** Whether a {@code {noop}} (unhashed) secret may be registered. */
    boolean allowsPlainSecrets() {
        return this != STRICT;
    }
}
