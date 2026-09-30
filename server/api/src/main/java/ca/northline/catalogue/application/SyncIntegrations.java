package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.WebhookRequest;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.domain.CommerceProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shopify / Square / Lightspeed catalogue sync (S-35): connect with OAuth, import the catalogue as drafts, keep price
 * and stock in step (webhooks, hourly reads), hide listings whose product left the platform.
 */
public interface SyncIntegrations {

    /** One entry per platform, connected or not. */
    List<Connection> connections(String merchantId);

    /** Starts OAuth: returns the platform's consent page. {@code shop} is the Shopify store (required there). */
    URI connect(String merchantId, String userId, CommerceProvider provider, @Nullable String shop);

    /** Revokes the grant, stops the sync; imported listings stay as they are. */
    Connection disconnect(String merchantId, CommerceProvider provider);

    /** A full read now ("Sync now"). */
    Connection sync(String merchantId, CommerceProvider provider);

    /** The OAuth redirect URI (public, on the api host): the state names the member and business. */
    interface CompleteCommerceConnection {

        enum Outcome {
            CONNECTED,
            DENIED,
            FAILED,
            EXPIRED;

            public String code() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        record Completion(@Nullable String merchantId, CommerceProvider provider, Outcome outcome) {}

        Completion complete(CommerceProvider provider, Map<String, String> params);
    }

    /** Webhooks (public, on the api host): verified by the platform's HMAC, deduplicated. */
    interface ReceiveCommerceWebhook {

        enum Receipt {
            ACCEPTED,
            DUPLICATE
        }

        /** Throws {@link CommerceCatalogSource.Unverified} when the signature doesn't verify. */
        Receipt receive(CommerceProvider provider, WebhookRequest request);
    }

    /** The scheduler's work (the tests call it directly). */
    interface CommerceJobs {

        /** Full reads of the connections that are due; returns how many ran. */
        int syncDue();

        int purge();
    }
}
