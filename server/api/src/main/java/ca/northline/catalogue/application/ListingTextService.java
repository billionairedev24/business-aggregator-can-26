package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VettingFlag;
import ca.northline.trust.api.ListingTexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ListingTexts} over the listing aggregates: the listing's own words, category, price and vetting result. */
@Service
@RequiredArgsConstructor
class ListingTextService implements ListingTexts {

    private final SubmittedListings submitted;
    private final ListingRepository listings;
    private final CategoryCatalog categories;

    @Override
    @Transactional(readOnly = true)
    public List<ListingText> submittedAfter(java.time.Instant at, String id, int limit) {
        var out = new ArrayList<ListingText>();
        for (var s : submitted.after(at, id, limit)) {
            listings.find(s.listingId()).ifPresent(l -> out.add(text(l, s.submittedAt())));
        }
        return out;
    }

    private ListingText text(Listing listing, java.time.Instant submittedAt) {
        var category = profile(listing.categoryId());
        return new ListingText(
                listing.getId(),
                listing.getMerchantId(),
                listing.kind().code(),
                submittedAt,
                listing.displayName(),
                details(listing),
                category == null ? null : category.name(),
                listing.priceCents(),
                category == null ? null : category.medianPriceCents(),
                listing.getState().getFlags().stream().map(VettingFlag::code).toList());
    }

    private static String details(Listing listing) {
        return switch (listing) {
            case ServiceListing s -> Objects.toString(s.getDetails().included(), "");
            case ProductListing p -> {
                var d = p.getDetails();
                var parts = new ArrayList<String>();
                if (d.description() != null) {
                    parts.add(d.description());
                }
                parts.addAll(d.bullets());
                yield String.join("\n", parts);
            }
        };
    }

    private @Nullable CategoryProfile profile(@Nullable String categoryId) {
        return categoryId == null ? null : categories.profile(categoryId).orElse(null);
    }
}
