package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Two-way stock with Shopify, Square and Lightspeed (S-158): Northline's sales, and the cancellations and returns a
 * shop restocks, change the stock on the connected platform too — at the location the merchant sells from — through
 * an idempotent push with retries; what the platform refuses is listed in Settings › Integrations.
 */
public interface StockSync {

    String LOCATION_UNKNOWN = "Choose one of the store's locations.";
    String PUSH_APPLIED = "This change already reached the platform; it can only be dismissed.";
    String PUSH_CLOSED = "This change is no longer waiting for you.";

    /** Where the connection stands: the location, whether stock can be written, counts and what needs the merchant. */
    Status status(String merchantId, CommerceProvider provider);

    /** The platform's locations (read live), the chosen one marked. */
    List<LocationView> locations(String merchantId, CommerceProvider provider);

    /** Picks the location Northline sells from (owner); refused changes waiting for a location go again. */
    Status chooseLocation(String merchantId, CommerceProvider provider, String locationId, String actorId, String role);

    /** A refused or failed change goes again ("Retry"); 409 when the platform already applied it. */
    PushView retry(String merchantId, String pushId, String actorId);

    /** The merchant fixed it on the platform by hand ("Dismiss"). */
    PushView dismiss(String merchantId, String pushId, String actorId);

    /** Records the changes of a Northline stock movement for every connected platform the SKU is linked to. */
    interface RecordStockChanges {

        /**
         * @param delta negative for a sale, positive for a restock
         * @param reason {@code sale} | {@code cancellation} | {@code return}
         * @param key unique per movement and line, e.g. {@code sale:<order>:<line>} (the platform's idempotency key
         *     adds the provider)
         */
        record Change(
                String merchantId,
                String offerId,
                @Nullable String variantId,
                int delta,
                String reason,
                String sourceRef,
                String key) {}

        /** Returns how many pushes were queued (0 when the SKU isn't linked to any connected platform). */
        int record(Change change);
    }

    /** The push job (the scheduler, or tests directly): sends the due pushes; returns how many were tried. */
    interface PushStock {
        int pushDue();
    }

    /**
     * @param locationId null until chosen (a store with one location uses it on the first push)
     * @param canWrite the grant allows stock changes (false: reconnect to grant them)
     * @param done changes applied in the last 7 days
     */
    record Status(
            CommerceProvider provider,
            boolean connected,
            @Nullable String locationId,
            @Nullable String locationName,
            boolean canWrite,
            int pending,
            int done,
            List<PushView> attention) {

        public Status {
            attention = List.copyOf(attention);
        }
    }

    record LocationView(String id, String name, boolean chosen) {}

    /**
     * @param state {@code pending | done | conflict | failed | dismissed}
     * @param conflict the refusal's code (see {@link CommerceStockPushStore.Push#conflict()})
     * @param applied the platform applied it (an {@code oversold} conflict included): only "Dismiss" is offered
     * @param listingName the listing's name, for the merchant (null when the listing is gone)
     */
    record PushView(
            String id,
            String offerId,
            @Nullable String listingName,
            String sku,
            int delta,
            String reason,
            String sourceRef,
            String state,
            @Nullable String conflict,
            @Nullable String detail,
            int attempts,
            @Nullable Integer platformAfter,
            boolean applied,
            Instant createdAt) {}
}
