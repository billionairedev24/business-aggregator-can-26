package ca.northline.catalogue.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-156 (S-92's open item): the reviewer's latest decision on one of the business's listings — on a rejected listing
 * the Studio shows the reasons and the note (they were only in the email before).
 */
public interface ViewListingReview {

    Optional<Review> latest(String merchantId, String listingId);

    /** @param reasons {@code prohibited | misleading | pricing | licence | images | other} */
    record Review(
            String decision, List<String> reasons, @Nullable String note, Instant decidedAt) {
        public Review {
            reasons = List.copyOf(reasons);
        }
    }
}
