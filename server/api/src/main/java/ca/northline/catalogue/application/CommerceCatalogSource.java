package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-35): one merchant's catalogue on Shopify (Admin GraphQL API), Square (Catalog + Inventory API) or
 * Lightspeed Retail X-Series, reached with the merchant's OAuth grant. One adapter per platform, chosen by
 * {@code northline.commerce.provider} ({@code local} fakes with fixture catalogues, {@code oauth} the real APIs).
 * Adapters normalise the platform's shapes into {@link ExternalProduct}s and retry rate-limited calls themselves
 * (Shopify's cost-based throttle, 429s with back-off).
 */
public interface CommerceCatalogSource {

    CommerceProvider provider();

    /** Whether the platform's app credentials are configured (otherwise the Studio shows "Not available yet"). */
    boolean available();

    /** The consent page. {@code shop} is the Shopify store domain ({@code x.myshopify.com}); null for the others. */
    URI authorizationUrl(String state, URI redirectUri, @Nullable String shop);

    /**
     * Checks the callback (Shopify's {@code hmac} over the query) and exchanges the code.
     *
     * @param params every query parameter of the callback, as received
     * @param shop the store the merchant started with (Shopify), which the callback must name
     */
    Grant exchange(Map<String, String> params, URI redirectUri, @Nullable String shop);

    /** A fresh access token when the current one expires within a minute; the same credentials otherwise. */
    Credentials refresh(Credentials credentials);

    /** One page of the full catalogue: active products only, with variants, prices, stock and image URLs. */
    Page products(Credentials credentials, @Nullable String cursor);

    /** One product as it is now; empty when it was deleted or is no longer active. */
    Optional<ExternalProduct> product(Credentials credentials, String externalId);

    /**
     * Registers the change notifications Northline needs (Shopify and Lightspeed per shop; Square's subscription is
     * app-level, set up once in its Developer Console, so this only checks it applies). False when it couldn't.
     */
    boolean subscribe(Credentials credentials, URI callbackUrl);

    /** Revokes the grant where the platform allows it (Lightspeed has no endpoint: the token is only destroyed). */
    void revoke(Credentials credentials);

    /** Verifies a webhook delivery's signature and reads what changed; throws {@link Unverified}. */
    Delivery verify(WebhookRequest request);

    // ── values ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What is sealed at rest. {@code account} is what the API URL needs (Shopify shop domain, Lightspeed domain prefix,
     * Square merchant id).
     */
    record Credentials(
            String accessToken,
            @Nullable String refreshToken,
            @Nullable Instant expiresAt,
            String account) {}

    record Grant(Credentials credentials, String accountId, String accountLabel, Set<String> scopes) {}

    record Page(List<ExternalProduct> items, @Nullable String next) {}

    /**
     * A product family on the platform. {@code description} is plain text. Prices are CAD cents, before tax; stock is
     * the sum over the platform's locations / outlets.
     */
    record ExternalProduct(
            String id,
            String title,
            @Nullable String description,
            @Nullable String vendor,
            List<URI> images,
            List<ExternalVariant> variants,
            @Nullable Instant updatedAt) {
        public ExternalProduct {
            images = List.copyOf(images);
            variants = List.copyOf(variants);
        }
    }

    /**
     * @param options option name → value in the platform's order ({@code Size → 22 in}); empty for a single variant
     * @param stockRef what the platform's inventory notifications name (Shopify inventory item, Square variation,
     *     Lightspeed product)
     */
    record ExternalVariant(
            String id,
            @Nullable String sku,
            @Nullable String barcode,
            String title,
            Map<String, String> options,
            long priceCents,
            int stock,
            @Nullable String stockRef) {
        public ExternalVariant {
            options = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
        }
    }

    /** A webhook as received: lower-case header names, the raw body, the URL it was sent to (Square signs it). */
    @SuppressWarnings("ArrayRecordComponent") // raw body, never compared
    record WebhookRequest(Map<String, String> headers, byte[] body, URI url) {
        public @Nullable String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** A verified delivery: which platform account it concerns, its dedupe id and what changed. */
    record Delivery(String accountId, String deliveryId, List<Change> changes) {}

    sealed interface Change {
        record ProductChanged(String externalId) implements Change {}

        record ProductRemoved(String externalId) implements Change {}

        record StockChanged(String stockRef) implements Change {}

        /** Something changed and the platform doesn't say what (Square {@code catalog.version.updated}). */
        record CatalogChanged() implements Change {}

        /** The merchant removed the app, or revoked the grant, on the platform. */
        record Uninstalled() implements Change {}

        /** Shopify {@code shop/redact}: forget the shop (48 h after uninstall). */
        record Redact() implements Change {}
    }

    // ── failures ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** The grant was revoked or refused ({@code invalid_grant}, 401 after a refresh): the merchant must reconnect. */
    final class GrantRevoked extends RuntimeException {
        public GrantRevoked(String message) {
            super(message);
        }
    }

    /** A callback or webhook that doesn't verify (signature, shop, parameters). */
    final class Unverified extends RuntimeException {
        public Unverified(String message) {
            super(message);
        }
    }
}
