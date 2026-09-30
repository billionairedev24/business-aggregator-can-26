package ca.northline.shared.web;

import ca.northline.shared.integration.OAuthCallback;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The OAuth redirect URI of the merchants' outside platforms (S-35 Shopify / Square / Lightspeed, S-36 Square / Clover):
 * {@code https://api.<zone>/api/v1/commerce/oauth/<platform>/callback}, on the api host (docs/runbooks/edge.md). A
 * platform takes one redirect URL per app, and the same Square app serves the catalogue sync and the kitchens' menu
 * import, so the module whose single-use state this is ({@link OAuthCallback}) completes it. Public: the state is the
 * authorisation. Answers 303 to the Studio ({@code no-store}, {@code no-referrer}); an unknown or used state goes to
 * {@code /?commerce=<platform>&result=expired}.
 */
@RestController
class OAuthCallbackController {

    private final List<OAuthCallback> callbacks;
    private final String studio;

    OAuthCallbackController(
            List<OAuthCallback> callbacks, @Value("${STUDIO_ORIGIN:http://localhost:3100}") String studio) {
        this.callbacks = List.copyOf(callbacks);
        this.studio = studio.endsWith("/") ? studio.substring(0, studio.length() - 1) : studio;
    }

    @GetMapping("/api/v1/commerce/oauth/{platform}/callback")
    ResponseEntity<Void> callback(@PathVariable String platform, @RequestParam Map<String, String> params) {
        var target = callbacks.stream()
                .map(c -> c.complete(platform, params))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseGet(() -> URI.create(UriComponentsBuilder.fromUriString(studio + "/")
                        .queryParam("commerce", platform.replaceAll("[^a-z]", ""))
                        .queryParam("result", "expired")
                        .build()
                        .encode()
                        .toUriString()));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(target)
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
