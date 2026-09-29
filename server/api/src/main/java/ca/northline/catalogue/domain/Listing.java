package ca.northline.catalogue.domain;

import ca.northline.catalogue.api.ListingDeleted;
import ca.northline.catalogue.api.ListingFlagged;
import ca.northline.catalogue.api.ListingHidden;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.catalogue.api.ListingSubmitted;
import ca.northline.shared.DomainEvent;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Something a merchant sells on Northline: a {@link ServiceListing} or a {@link ProductListing} (an offer). The
 * lifecycle (vetting, visibility) is shared; every transition returns the event to publish.
 */
public sealed interface Listing permits ProductListing, ServiceListing {

    String getId();

    String getMerchantId();

    ListingKind kind();

    ListingState getState();

    String displayName();

    @Nullable
    String sku();

    /** Customer price; null for quote-priced services. */
    @Nullable
    Long priceCents();

    @Nullable
    String categoryId();

    /** Submit for vetting. Rejects (422) when the listing is incomplete, (409) when it is not a draft or rejected. */
    default ListingSubmitted submit(Completeness completeness, String actorId, Instant at) {
        if (!completeness.missing().isEmpty()) {
            throw new RuleViolation(completeness.missing());
        }
        getState().submit(at);
        return new ListingSubmitted(Ids.next(), at, getId(), getMerchantId(), kind().code(), actorId);
    }

    /** Outcome of the automated checks: approved and published, or flagged for manual review. */
    default Optional<DomainEvent> vetted(List<VettingFlag> flags, Instant at) {
        var state = getState();
        if (state.getVetting() != Vetting.PENDING) {
            return Optional.empty();
        }
        if (state.vetted(flags, at)) {
            return Optional.of(new ListingPublished(Ids.next(), at, getId(), getMerchantId(), kind().code()));
        }
        var codes = flags.stream().map(VettingFlag::code).toList();
        return Optional.of(new ListingFlagged(Ids.next(), at, getId(), getMerchantId(), kind().code(), codes));
    }

    default Optional<ListingPublished> publish(Instant at) {
        return getState().publish(at)
                ? Optional.of(new ListingPublished(Ids.next(), at, getId(), getMerchantId(), kind().code()))
                : Optional.empty();
    }

    default Optional<ListingHidden> hide(Instant at) {
        return getState().hide(at)
                ? Optional.of(new ListingHidden(Ids.next(), at, getId(), getMerchantId(), kind().code()))
                : Optional.empty();
    }

    default ListingDeleted deleted(String actorId, Instant at) {
        return new ListingDeleted(Ids.next(), at, getId(), getMerchantId(), kind().code(), actorId);
    }
}
