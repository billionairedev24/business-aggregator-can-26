package ca.northline.console.application;

import ca.northline.catalogue.api.ListingVetting;
import ca.northline.catalogue.api.ListingVetting.FlaggedListing;
import ca.northline.food.api.MenuPriceReviews;
import ca.northline.food.api.MenuPriceReviews.HeldDish;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.ListingFlags;
import ca.northline.trust.api.ListingFlags.ListingFlag;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ListingVettingQueue} composed from the owning modules' APIs (S-37: no cross-module SQL). A listing decision
 * is the catalogue's (state, decision row, audit, owners' email) plus the resolution of its open trust flags — one
 * transaction, so both commit or neither does.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class VettingQueueService implements ListingVettingQueue {

    static final int LIMIT = 200;
    static final Duration WEEK = Duration.ofDays(7);

    private final ListingVetting listings;
    private final MenuPriceReviews dishes;
    private final ListingFlags trustFlags;
    private final BusinessNames names;
    private final MerchantPlaces places;
    private final Clock clock;

    @Override
    public Queue queue(MerchantScope scope) {
        var open = trustFlags.open(500).stream()
                .filter(f -> scope.everyone() || (f.merchantId() != null && in(scope, f.merchantId())))
                .collect(Collectors.groupingBy(ListingFlag::listingId));
        var businesses = new Businesses();
        var items = new ArrayList<Item>();
        for (var l : listings.queue(scope, open.keySet(), LIMIT)) {
            items.add(item(l, open.getOrDefault(l.listingId(), List.of()), businesses));
        }
        for (var d : dishes.held(scope, LIMIT)) {
            items.add(item(d, businesses));
        }
        items.sort(Comparator.comparing((Item i) -> !"pending".equals(i.state()))
                .thenComparing(i -> i.submittedAt() == null ? java.time.Instant.MAX : i.submittedAt()));
        var flagged = items.stream().filter(i -> "pending".equals(i.state())).count();
        return new Queue(listings.autoApproved(scope, clock.instant().minus(WEEK)), flagged, items);
    }

    @Override
    @Transactional
    public Item decide(Decision d) {
        var businesses = new Businesses();
        return switch (d.kind()) {
            case "listing" -> {
                var listing = listings.find(d.id()).orElseThrow(() -> new NotFound("listing", d.id()));
                var flags = trustFlags.open(500).stream()
                        .filter(f -> f.listingId().equals(d.id()))
                        .toList();
                if (flags.isEmpty()
                        && !("pending".equals(listing.vetting())
                                && !listing.flags().isEmpty())) {
                    throw new Conflict("not_in_review", NOT_IN_REVIEW);
                }
                var decided = listings.decide(
                        new ListingVetting.Decision(d.id(), d.approve(), d.reasons(), d.note(), d.staffId(), d.role()));
                trustFlags.resolve(d.id(), !d.approve(), d.staffId(), d.role(), d.note());
                yield item(decided, List.of(), businesses);
            }
            case "dish" -> {
                // S-104: the same reasons as a listing — they reach the business's email as message keys
                if (!d.approve() && d.reasons().stream().anyMatch(r -> !ListingVetting.REASONS.contains(r))) {
                    throw RuleViolation.of("reasons", "option", ListingVetting.UNKNOWN_REASON);
                }
                yield item(
                        dishes.decide(d.id(), d.approve(), d.reasons(), d.note(), d.staffId(), d.role()), businesses);
            }
            default -> throw new NotFound(d.kind(), d.id());
        };
    }

    private Item item(FlaggedListing l, List<ListingFlag> flags, Businesses businesses) {
        var deviation = l.priceCents() != null && l.categoryMedianCents() != null && l.categoryMedianCents() > 0
                ? (int) Math.round((l.priceCents() - l.categoryMedianCents()) * 100.0 / l.categoryMedianCents())
                : null;
        var waiting = "pending".equals(l.vetting()) || !flags.isEmpty();
        return new Item(
                l.listingId(),
                l.kind(),
                l.merchantId(),
                businesses.name(l.merchantId()),
                businesses.province(l.merchantId()),
                l.name(),
                l.priceCents(),
                l.category(),
                l.categoryMedianCents(),
                deviation,
                l.regulator(),
                l.flags(),
                l.revetReasons(),
                flags.stream()
                        .map(f -> new TrustFlag(f.flagId(), f.rule(), f.source(), f.explanation(), f.categories()))
                        .toList(),
                waiting ? "pending" : l.decision() == null ? l.vetting() : l.decision(),
                l.submittedAt(),
                l.decidedAt(),
                l.reasons(),
                l.note());
    }

    private Item item(HeldDish d, Businesses businesses) {
        return new Item(
                d.itemId(),
                "dish",
                d.merchantId(),
                businesses.name(d.merchantId()),
                businesses.province(d.merchantId()),
                d.name(),
                d.priceCents(),
                null,
                d.medianCents(),
                d.deviationPct(),
                null,
                List.of("price_check"),
                List.of(),
                List.of(),
                "held".equals(d.state()) ? "pending" : d.state(),
                d.since(),
                null,
                List.of(),
                null);
    }

    private static boolean in(MerchantScope scope, String merchantId) {
        return switch (scope) {
            case MerchantScope.Everyone _ -> true;
            case MerchantScope.Only(var ids) -> ids.contains(merchantId);
        };
    }

    /** Business names and provinces, read once per business per request. */
    private final class Businesses {
        private final Map<String, String> nameById = new HashMap<>();
        private final Map<String, MerchantPlaces.MerchantPlace> placeById = new HashMap<>();

        String name(String merchantId) {
            return nameById.computeIfAbsent(
                    merchantId, id -> names.displayName(id).orElse(id));
        }

        @Nullable
        String province(String merchantId) {
            var place = placeById.computeIfAbsent(merchantId, places::of);
            return place.ownProvince() ? place.province() : null;
        }
    }
}
