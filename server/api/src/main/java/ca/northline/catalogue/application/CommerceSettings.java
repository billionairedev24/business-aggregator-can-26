package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.net.URI;
import java.time.Duration;

/**
 * S-35 settings the application layer needs (from {@code northline.commerce.*}).
 *
 * @param apiUrl the api's public origin ({@code API_PUBLIC_URL}): OAuth redirect URIs and webhook URLs are on the api
 *     host (docs/runbooks/edge.md)
 * @param studioUrl the Studio origin ({@code STUDIO_ORIGIN}): where the callback sends the browser back
 * @param pollInterval how often a connection without webhooks is read in full ({@code COMMERCE_POLL_INTERVAL}, 1 h)
 * @param reconcileInterval how often a connection with webhooks is still read in full (a missed webhook, deletions)
 */
public record CommerceSettings(URI apiUrl, URI studioUrl, Duration pollInterval, Duration reconcileInterval) {

    public URI redirectUri(CommerceProvider provider) {
        return URI.create(base(apiUrl) + "/api/v1/commerce/oauth/" + provider.code() + "/callback");
    }

    public URI webhookUrl(CommerceProvider provider) {
        return URI.create(base(apiUrl) + "/api/v1/webhooks/commerce/" + provider.code());
    }

    /** Platforms call HTTPS endpoints only; a laptop ({@code http://localhost}) relies on polling. */
    public boolean webhooksReachable() {
        return "https".equalsIgnoreCase(apiUrl.getScheme());
    }

    public String studio(String path) {
        return base(studioUrl) + path;
    }

    private static String base(URI uri) {
        var s = uri.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
