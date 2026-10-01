package ca.northline.catalogue.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * S-92: the console's listing vetting queue as the catalogue owns it — listings the automated checks flagged (S-39
 * re-vets included) and listings with an open trust flag, and the reviewer's decision (approve / reject with reasons),
 * audit-logged and, for a rejection, emailed to the business's owners.
 */
public interface ListingVetting {

    /** Decided listings stay in the queue this many days. */
    int RECENT_DAYS = 7;

    String DECISION_REQUIRED = "Choose approve or reject.";
    String REASONS_REQUIRED = "Choose why the listing is rejected.";
    String UNKNOWN_REASON = "Pick reasons from the list.";

    /** Why a reviewer rejects a listing (shown to the business). */
    Set<String> REASONS = Set.of("prohibited", "misleading", "pricing", "licence", "images", "other");

    /**
     * Flagged listings waiting in scope (oldest submission first), the listings among {@code alsoListingIds} (open
     * trust flags) whatever their vetting, and listings decided in the last {@link #RECENT_DAYS} days.
     */
    List<FlaggedListing> queue(MerchantScope scope, Collection<String> alsoListingIds, int limit);

    java.util.Optional<FlaggedListing> find(String listingId);

    /** Listings the automated checks approved since {@code since} with no reviewer involved (design "auto-approved"). */
    long autoApproved(MerchantScope scope, Instant since);

    FlaggedListing decide(Decision decision);

    /**
     * @param reasons from {@link #REASONS}; required to reject
     * @param note to the business (rejections) or for the record
     */
    record Decision(
            String listingId, boolean approve, List<String> reasons, @Nullable String note, String staffId, String role) {
        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * @param kind {@code product | service}
     * @param flags the automated vetting flags ({@code price_outlier}, {@code missing_licence}, …)
     * @param revetReasons S-39: why an approved listing is back in vetting ({@code price | category | images})
     * @param vetting {@code pending | approved | rejected | draft}
     * @param status {@code live | hidden}
     * @param decision the latest reviewer decision ({@code approved | rejected}), when there is one
     */
    record FlaggedListing(
            String listingId,
            String kind,
            String merchantId,
            String name,
            @Nullable Long priceCents,
            @Nullable String category,
            @Nullable Long categoryMedianCents,
            @Nullable String regulator,
            List<String> flags,
            List<String> revetReasons,
            String vetting,
            String status,
            @Nullable Instant submittedAt,
            @Nullable String decision,
            @Nullable Instant decidedAt,
            List<String> reasons,
            @Nullable String note) {
        public FlaggedListing {
            flags = List.copyOf(flags);
            revetReasons = List.copyOf(revetReasons);
            reasons = List.copyOf(reasons);
        }
    }
}
