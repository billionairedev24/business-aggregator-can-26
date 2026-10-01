package ca.northline.trust.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-92: the open trust &amp; safety flags on listings (S-133 AI screening, target type {@code listing}) for the console's
 * listing vetting queue, and their resolution when the reviewer decides the listing.
 */
public interface ListingFlags {

    /** Open flags on listings, oldest first. */
    List<ListingFlag> open(int limit);

    /**
     * Decides every open flag on the listing: {@code actioned} when the reviewer rejected it, {@code dismissed} when
     * approved. Audit-logged and published ({@link FlagDecided}) like any staff decision. Returns how many it decided.
     */
    int resolve(String listingId, boolean actioned, String staffId, String role, @Nullable String note);

    /**
     * @param explanation why it was raised, in words (the model's, or the rule's)
     * @param categories the screening's categories ({@code misleading_claim}, …)
     */
    record ListingFlag(
            String flagId,
            String listingId,
            @Nullable String merchantId,
            String rule,
            String source,
            String explanation,
            List<String> categories,
            Instant createdAt) {
        public ListingFlag {
            categories = List.copyOf(categories);
        }
    }
}
