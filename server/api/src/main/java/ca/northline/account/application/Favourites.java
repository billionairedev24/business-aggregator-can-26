package ca.northline.account.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Favourite providers &amp; shops (design 06 {@code favourites}): "offered first when you book, and you're notified
 * when they fund rewards or open new slots". Inbound port and its outbound store.
 */
public final class Favourites {
    private Favourites() {}

    /**
     * @param type {@code provider | seller | kitchen | both}
     * @param tier {@code registered | trusted | master}
     * @param slug the business page ({@code /providers/<slug>}, {@code /food/<slug>}), null without one
     * @param categoryId the business's main category (its leaf slug names it on the web)
     * @param visits the person's bookings and orders with it
     * @param lastAt the latest of them
     * @param openQuoteId a quote from it the person can still accept
     */
    public record Favourite(
            String merchantId,
            String name,
            String type,
            String tier,
            @Nullable String slug,
            @Nullable String categoryId,
            int visits,
            @Nullable Instant lastAt,
            @Nullable String openQuoteId,
            Instant addedAt) {}

    public interface ManageFavourites {
        List<Favourite> list(String userId);

        /** Idempotent; 404 when the business isn't active. */
        void add(String userId, String merchantId);

        /** Idempotent. */
        void remove(String userId, String merchantId);
    }

    /** Outbound port: {@code account.favourites}. */
    public interface FavouriteStore {
        /** Merchant ids and when each was added, newest first. */
        List<Stored> of(String userId);

        void add(String userId, String merchantId, Instant at);

        void remove(String userId, String merchantId);

        int count(String userId);
    }

    public record Stored(String merchantId, Instant addedAt) {}
}
