package ca.northline.catalogue.adapters.commerce;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.commerce.*} (S-35, docs/runbooks/commerce-sync.md).
 *
 * @param provider {@code local} (fake Shopify / Square / Lightspeed with fixture catalogues; refused under
 *     staging/prod) | {@code oauth} (the real APIs, each offered once its app credentials are set) —
 *     {@code COMMERCE_PROVIDER}
 * @param apiUrl the api's public origin ({@code API_PUBLIC_URL}): OAuth redirect URIs and webhook URLs
 * @param studioUrl the Studio origin ({@code STUDIO_ORIGIN}): where the OAuth callback sends the browser back
 * @param pollInterval full read of a connection without webhooks ({@code COMMERCE_POLL_INTERVAL}; the backlog's
 *     "stock changes sync hourly")
 * @param reconcileInterval full read of a connection with webhooks (missed deliveries, deletions)
 * @param maxRetries attempts after a 429 / 503 or a Shopify throttle before the read fails
 * @param maxBackoff the longest single wait between attempts
 */
@ConfigurationProperties("northline.commerce")
record CommerceProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("http://localhost:8080") URI apiUrl,
        @DefaultValue("http://localhost:3100") URI studioUrl,
        @DefaultValue("PT1H") Duration pollInterval,
        @DefaultValue("P1D") Duration reconcileInterval,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("PT30S") Duration maxBackoff,
        @DefaultValue Images images,
        @DefaultValue Shopify shopify,
        @DefaultValue Square square,
        @DefaultValue Lightspeed lightspeed) {

    String effectiveProvider() {
        return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Product images are downloaded from these hosts only (HTTPS, no redirects, ≤ 15 MB) — the platforms' CDNs.
     * {@code allowHttp} exists for the WireMock tests.
     */
    record Images(
            @DefaultValue({
                "cdn.shopify.com",
                "items-images-production.s3.us-west-2.amazonaws.com",
                "square-marketplace.s3.amazonaws.com",
                "vendimageuploadcdn.global.ssl.fastly.net",
                "images.retail.lightspeed.app"
            })
            List<String> hosts,

            @DefaultValue("false") boolean allowHttp) {}

    /**
     * Shopify app (Partner Dashboard / Dev Dashboard) — {@code SHOPIFY_CLIENT_ID} / {@code SHOPIFY_CLIENT_SECRET}. The
     * secret also signs the OAuth callback and every webhook. {@code shopUrl} is where a shop's Admin API lives
     * ({@code {shop}} = {@code x.myshopify.com}); tests point it at WireMock.
     */
    record Shopify(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @DefaultValue("2026-07") String apiVersion,
            @DefaultValue("read_products,read_inventory") String scopes,
            @DefaultValue("https://{shop}") String shopUrl) {

        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return clientId == null ? "" : clientId.strip();
        }

        String secret() {
            return clientSecret == null ? "" : clientSecret.strip();
        }
    }

    /**
     * Square application (Developer Console) — {@code SQUARE_CLIENT_ID} / {@code SQUARE_CLIENT_SECRET}, and the
     * webhook subscription's signature key {@code SQUARE_WEBHOOK_SIGNATURE_KEY}. {@code baseUrl}
     * {@code https://connect.squareupsandbox.com} for the sandbox.
     */
    record Square(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @Nullable String webhookSignatureKey,
            @DefaultValue("https://connect.squareup.com") String baseUrl,
            @DefaultValue("2025-10-16") String apiVersion,

            @DefaultValue("ITEMS_READ INVENTORY_READ MERCHANT_PROFILE_READ")
            String scopes) {

        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return clientId == null ? "" : clientId.strip();
        }

        String secret() {
            return clientSecret == null ? "" : clientSecret.strip();
        }

        String signatureKey() {
            return webhookSignatureKey == null ? "" : webhookSignatureKey.strip();
        }
    }

    /**
     * Lightspeed Retail (X-Series) add-on (developer portal) — {@code LIGHTSPEED_CLIENT_ID} /
     * {@code LIGHTSPEED_CLIENT_SECRET}; the secret also signs webhooks. {@code apiUrl} is the store's API
     * ({@code {domain_prefix}}); tests point both URLs at WireMock.
     */
    record Lightspeed(
            @Nullable String clientId,
            @Nullable String clientSecret,

            @DefaultValue("https://secure.retail.lightspeed.app/connect")
            String authUrl,

            @DefaultValue("https://{domain_prefix}.retail.lightspeed.app")
            String apiUrl,

            @DefaultValue("") String scopes) {

        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return clientId == null ? "" : clientId.strip();
        }

        String secret() {
            return clientSecret == null ? "" : clientSecret.strip();
        }
    }

    private static boolean present(@Nullable String s) {
        return s != null && !s.isBlank();
    }
}
