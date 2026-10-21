package ca.northline.catalogue.application;

import ca.northline.shared.NotFound;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ViewListingReview} over S-92's decisions; another business's listing is a 404, like every listing route. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingReviewService implements ViewListingReview {

    private final VettingDecisionStore decisions;
    private final ListingRepository listings;

    @Override
    public Optional<Review> latest(String merchantId, String listingId) {
        if (listings.find(merchantId, listingId).isEmpty()) {
            throw new NotFound("listing", listingId);
        }
        return decisions
                .latest(listingId)
                .filter(d -> d.merchantId().equals(merchantId))
                .map(d -> new Review(d.decision(), d.reasons(), d.note(), d.decidedAt()));
    }
}
