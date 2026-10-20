package ca.northline.catalogue.application;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ViewListingReview} over S-92's decisions; another business's listing reads as absent. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingReviewService implements ViewListingReview {

    private final VettingDecisionStore decisions;

    @Override
    public Optional<Review> latest(String merchantId, String listingId) {
        return decisions
                .latest(listingId)
                .filter(d -> d.merchantId().equals(merchantId))
                .map(d -> new Review(d.decision(), d.reasons(), d.note(), d.decidedAt()));
    }
}
