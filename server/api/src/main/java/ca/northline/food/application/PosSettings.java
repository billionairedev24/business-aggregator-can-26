package ca.northline.food.application;

import ca.northline.food.domain.PosProvider;
import java.net.URI;

/**
 * S-36 settings the application layer needs (from {@code northline.pos.*}).
 *
 * @param apiUrl the api's public origin ({@code API_PUBLIC_URL}): the OAuth redirect URI is the shared
 *     {@code <api>/api/v1/commerce/oauth/<pos>/callback} (Square's one redirect URL serves the catalogue sync too)
 * @param studioUrl the Studio origin ({@code STUDIO_ORIGIN}): where the callback sends the browser back
 */
public record PosSettings(URI apiUrl, URI studioUrl) {

    public URI redirectUri(PosProvider provider) {
        return URI.create(base(apiUrl) + "/api/v1/commerce/oauth/" + provider.code() + "/callback");
    }

    public String studio(String path) {
        return base(studioUrl) + path;
    }

    private static String base(URI uri) {
        var s = uri.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
