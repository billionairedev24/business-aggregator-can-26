package ca.northline.catalogue.web;

import ca.northline.catalogue.application.CommerceSettings;
import ca.northline.catalogue.application.SyncIntegrations.CompleteCommerceConnection;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.integration.OAuthCallback;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Shopify / Square / Lightspeed consents started by Studio › Bulk upload (S-35), completed through the shared callback
 * {@code /api/v1/commerce/oauth/<platform>/callback}. Answers Studio › Listings › Bulk upload with the outcome
 * ({@code ?commerce=shopify&result=connected|denied|failed|expired}); a state this module never stored is left to the
 * others (S-36 kitchens share the Square app).
 */
@Component
@RequiredArgsConstructor
class CommerceOAuthCallback implements OAuthCallback {

    private final CompleteCommerceConnection complete;
    private final CommerceSettings settings;

    @Override
    public Optional<URI> complete(String platform, Map<String, String> params) {
        CommerceProvider provider;
        try {
            provider = CodedEnum.fromCode(CommerceProvider.class, platform);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        var done = complete.complete(provider, params);
        if (done.merchantId() == null) {
            return Optional.empty(); // not our state
        }
        return Optional.of(URI.create(
                UriComponentsBuilder.fromUriString(settings.studio("/b/" + done.merchantId() + "/listings/bulk"))
                        .queryParam("commerce", provider.code())
                        .queryParam("result", done.outcome().code())
                        .build()
                        .encode()
                        .toUriString()));
    }
}
