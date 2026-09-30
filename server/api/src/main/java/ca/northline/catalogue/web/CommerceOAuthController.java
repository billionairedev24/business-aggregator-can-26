package ca.northline.catalogue.web;

import ca.northline.catalogue.application.CommerceSettings;
import ca.northline.catalogue.application.SyncIntegrations.CompleteCommerceConnection;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth redirect URI of Shopify, Square and Lightspeed (S-35): {@code https://api.<zone>/api/v1/commerce/oauth/
 * <platform>/callback}, on the api host like the webhooks (docs/runbooks/edge.md) — the platforms' app settings take one
 * fixed URL per app. Public: the single-use state names the member and business (and Shopify's {@code hmac} signs the
 * query). Answers 303 to Studio › Listings › Bulk upload with the outcome
 * ({@code ?commerce=shopify&result=connected|denied|failed|expired}).
 */
@RestController
@RequiredArgsConstructor
class CommerceOAuthController {

    private final CompleteCommerceConnection complete;
    private final CommerceSettings settings;

    @GetMapping("/api/v1/commerce/oauth/{provider}/callback")
    ResponseEntity<Void> callback(@PathVariable String provider, @RequestParam Map<String, String> params) {
        var p = CommerceController.provider(provider);
        var done = complete.complete(p, params);
        var target = UriComponentsBuilder.fromUriString(
                        settings.studio(done.merchantId() == null ? "/" : "/b/" + done.merchantId() + "/listings/bulk"))
                .queryParam("commerce", p.code())
                .queryParam("result", done.outcome().code());
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(target.build().encode().toUriString()))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
