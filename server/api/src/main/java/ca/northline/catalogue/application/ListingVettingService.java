package ca.northline.catalogue.application;

import ca.northline.catalogue.api.ListingVetting;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VettingFlag;
import ca.northline.developer.api.AuditTrail;
import ca.northline.messaging.api.ListingRejectedNotice;
import ca.northline.shared.DomainEvent;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.FlagDecided;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ListingVetting} (S-92): the reviewer's approve / reject on a flagged listing — the listing's state, the
 * decision row, the audit entry ({@code vetting.listing_approved|listing_rejected}) and, for a rejection, the owners'
 * email ({@link ListingRejectedNotice}), in one transaction. Also what "actioned" means for a trust flag on a listing
 * (the S-133 open question): the listing is rejected the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingVettingService implements ListingVetting {

    static final String TRUST_FLAG_REASON = "other";

    private final ListingRepository listings;
    private final VettingDecisionStore decisions;
    private final CategoryCatalog categories;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<FlaggedListing> queue(MerchantScope scope, Collection<String> alsoListingIds, int limit) {
        var since = clock.instant().minus(Duration.ofDays(RECENT_DAYS));
        return decisions.queueIds(scope, alsoListingIds, since, limit).stream()
                .map(listings::find)
                .flatMap(Optional::stream)
                .map(this::view)
                .toList();
    }

    @Override
    public Optional<FlaggedListing> find(String listingId) {
        return listings.find(listingId).map(this::view);
    }

    @Override
    public long autoApproved(MerchantScope scope, Instant since) {
        return decisions.autoApproved(scope, since);
    }

    @Override
    @Transactional
    public FlaggedListing decide(Decision d) {
        var listing = listings.find(d.listingId()).orElseThrow(() -> new NotFound("listing", d.listingId()));
        var reasons = new LinkedHashSet<String>();
        if (!d.approve()) {
            if (d.reasons().isEmpty()) {
                throw RuleViolation.of("reasons", "required", REASONS_REQUIRED);
            }
            for (var r : d.reasons()) {
                if (!REASONS.contains(r)) {
                    throw RuleViolation.of("reasons", "option", UNKNOWN_REASON);
                }
                reasons.add(r);
            }
        }
        apply(listing, d.approve(), List.copyOf(reasons), blankToNull(d.note()), d.staffId(), d.role());
        return view(listing);
    }

    /**
     * S-133 open question, decided by S-92: "actioned" on a listing's trust flag rejects the listing (customers stop
     * seeing it, the owners are told). A listing already rejected — the vetting reviewer's own rejection resolving the
     * flag — is left alone.
     */
    @ApplicationModuleListener
    void on(FlagDecided event) {
        if (!"listing".equals(event.targetType()) || !"actioned".equals(event.decision())) {
            return;
        }
        var found = listings.find(event.targetId());
        if (found.isEmpty()) {
            return;
        }
        var listing = found.get();
        switch (listing.getState().getVetting()) {
            case PENDING, APPROVED ->
                apply(listing, false, List.of(TRUST_FLAG_REASON), event.note(), event.actorId(), event.role());
            case DRAFT, REJECTED ->
                log.debug(
                        "Listing {} isn't live or in review; flag {} changes nothing",
                        event.targetId(),
                        event.aggregateId());
        }
    }

    private void apply(
            Listing listing,
            boolean approve,
            List<String> reasons,
            @Nullable String note,
            String staffId,
            String role) {
        var now = clock.instant();
        var before = listing.getState().getVetting().code();
        var flags =
                listing.getState().getFlags().stream().map(VettingFlag::code).toList();
        Optional<? extends DomainEvent> event =
                approve ? listing.approveByReviewer(now) : listing.rejectByReviewer(now);
        save(listing);
        event.ifPresent(events::publishEvent);
        var decision = approve ? "approved" : "rejected";
        decisions.insert(new VettingDecisionStore.Decision(
                Ids.next(),
                listing.getId(),
                listing.kind().code(),
                listing.getMerchantId(),
                decision,
                reasons,
                flags,
                note,
                staffId,
                role,
                now));
        audit.record(AuditTrail.Entry.of(
                        listing.getMerchantId(),
                        staffId,
                        role,
                        approve ? "vetting.listing_approved" : "vetting.listing_rejected",
                        listing.kind().code(),
                        listing.getId())
                .withChange(
                        Map.of("vetting", before, "flags", flags),
                        Map.of("vetting", listing.getState().getVetting().code(), "reasons", reasons)));
        if (!approve) {
            events.publishEvent(new ListingRejectedNotice(
                    Ids.next(),
                    now,
                    listing.getId(),
                    listing.getMerchantId(),
                    listing.kind().code(),
                    listing.displayName(),
                    reasons,
                    note));
        }
    }

    private FlaggedListing view(Listing listing) {
        var state = listing.getState();
        var category = profile(listing.categoryId());
        var latest = decisions.latest(listing.getId()).orElse(null);
        return new FlaggedListing(
                listing.getId(),
                listing.kind().code(),
                listing.getMerchantId(),
                listing.displayName(),
                listing.priceCents(),
                category == null ? null : category.name(),
                category == null ? null : category.medianPriceCents(),
                category == null ? null : category.regulatedRegistry(),
                state.getFlags().stream().map(VettingFlag::code).toList(),
                state.getRevetReasons().stream().map(MaterialField::code).toList(),
                state.getVetting().code(),
                state.getStatus().code(),
                state.getSubmittedAt(),
                latest == null ? null : latest.decision(),
                latest == null ? null : latest.decidedAt(),
                latest == null ? List.of() : latest.reasons(),
                latest == null ? null : latest.note());
    }

    private void save(Listing listing) {
        switch (listing) {
            case ProductListing p -> listings.save(p);
            case ServiceListing s -> listings.save(s);
        }
    }

    private @Nullable CategoryProfile profile(@Nullable String categoryId) {
        return categoryId == null ? null : categories.profile(categoryId).orElse(null);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
