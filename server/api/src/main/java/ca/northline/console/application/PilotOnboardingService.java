package ca.northline.console.application;

import ca.northline.catalogue.api.ListingReadiness;
import ca.northline.console.application.PilotStages.Listings;
import ca.northline.food.api.MenuReadiness;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.merchants.api.PilotCohort.Pilot;
import ca.northline.payments.api.ConnectReadiness;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.NotFound;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PilotOnboarding}: the merchants module's pilot rows with each owning module's state, read in bulk. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PilotOnboardingService implements PilotOnboarding {

    private final PilotCohort cohort;
    private final KitchenVisits kitchenVisits;
    private final ListingReadiness listings;
    private final MenuReadiness menus;
    private final ConnectReadiness stripe;
    private final Regions regions;
    private final NotificationContacts contacts;

    @Override
    public PilotBoard board(@Nullable String marketId) {
        var market = marketId == null || marketId.isBlank() ? null : marketId.strip();
        var rows = rows(cohort.list(market));
        var stages = new LinkedHashMap<String, Integer>();
        STEPS.forEach(s -> stages.put(s, 0));
        rows.forEach(r -> stages.merge(r.stage(), 1, Integer::sum));
        return new PilotBoard(
                markets(),
                market,
                stages,
                (int) rows.stream().filter(r -> r.stage().equals("live")).count(),
                (int) rows.stream().filter(PilotRow::blocked).count(),
                rows);
    }

    @Override
    public PilotDetail detail(String pilotId) {
        var pilot = cohort.find(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
        var row = rows(List.of(pilot)).getFirst();
        var notes = cohort.notes(pilotId);
        var names = names(notes.stream().map(PilotCohort.Note::authorId).toList());
        var merchantId = pilot.merchantId();
        var business = pilot.business();
        List<KitchenVisits.Visit> visits = merchantId != null && "kitchen".equals(pilot.businessType())
                ? kitchenVisits.visits(merchantId)
                : List.of();
        return new PilotDetail(
                row,
                notes.stream()
                        .map(n -> new PilotNoteView(
                                n.id(), n.authorId(), names.get(n.authorId()), n.body(), n.createdAt()))
                        .toList(),
                cohort.invites(pilotId),
                visits,
                kitchenVisits.items(),
                business != null && business.kitchenVisitRequired());
    }

    @Override
    public String csv(@Nullable String marketId) {
        var out = new StringBuilder(
                "pilot_id,market,business,type,merchant_id,city,stage,next_step,next_action,next_owner,blocked,"
                        + "blocker,blocker_owner,owner,invite,listings,listings_live,created_at\n");
        for (var r : board(marketId).items()) {
            var next = r.next();
            out.append(String.join(
                            ",",
                            List.of(
                                    r.id(),
                                    r.marketId(),
                                    cell(r.businessName()),
                                    r.businessType(),
                                    Objects.requireNonNullElse(r.merchantId(), ""),
                                    cell(r.city()),
                                    r.stage(),
                                    next == null ? "" : next.key(),
                                    next == null ? "" : Objects.requireNonNullElse(next.action(), ""),
                                    next == null ? "" : Objects.requireNonNullElse(next.owner(), ""),
                                    String.valueOf(r.blocked()),
                                    cell(r.blocker()),
                                    Objects.requireNonNullElse(r.blockerOwner(), ""),
                                    cell(r.ownerName()),
                                    Objects.requireNonNullElse(r.inviteState(), ""),
                                    String.valueOf(r.listings()),
                                    String.valueOf(r.listingsLive()),
                                    r.createdAt().toString())))
                    .append('\n');
        }
        return out.toString();
    }

    private List<PilotRow> rows(List<Pilot> pilots) {
        var merchantIds =
                pilots.stream().map(Pilot::merchantId).filter(Objects::nonNull).toList();
        var kitchens = pilots.stream()
                .filter(p -> p.merchantId() != null && "kitchen".equals(p.businessType()))
                .map(Pilot::merchantId)
                .filter(Objects::nonNull)
                .toList();
        var listingCounts = listings.counts(merchantIds);
        var menuCounts = menus.counts(kitchens);
        var accounts = stripe.of(merchantIds);
        var owners = names(
                pilots.stream().map(Pilot::ownerId).filter(Objects::nonNull).toList());
        var out = new ArrayList<PilotRow>();
        for (var p : pilots) {
            var merchantId = p.merchantId();
            var counts = merchantId == null
                    ? Listings.NONE
                    : "kitchen".equals(p.businessType())
                            ? menu(menuCounts.get(merchantId))
                            : listing(listingCounts.get(merchantId));
            var steps = PilotStages.checklist(new PilotStages.Inputs(
                    p,
                    merchantId == null ? null : accounts.get(merchantId),
                    counts,
                    regions.marketById(p.marketId()).map(MarketProfile::live).orElse(false)));
            var business = p.business();
            var invite = p.invite();
            var ownerId = p.ownerId();
            out.add(new PilotRow(
                    p.id(),
                    p.label(),
                    business == null || !business.detailsComplete() ? p.label() : business.displayName(),
                    p.businessType(),
                    p.marketId(),
                    merchantId,
                    business == null ? null : business.city(),
                    PilotStages.stage(steps),
                    PilotStages.next(steps),
                    steps,
                    p.blocker() != null || steps.stream().anyMatch(s -> PilotStages.BLOCKED.equals(s.state())),
                    p.blocker(),
                    p.blockerOwner(),
                    p.blockerSince(),
                    ownerId,
                    ownerId == null ? null : owners.get(ownerId),
                    invite == null ? null : invite.state(),
                    counts.total(),
                    counts.live(),
                    p.createdAt()));
        }
        return List.copyOf(out);
    }

    private List<PilotMarket> markets() {
        return regions.markets().stream()
                .filter(m -> m.status() != LaunchStatus.OFF)
                .map(m -> new PilotMarket(
                        m.id(), m.city(), m.province(), m.status().code()))
                .toList();
    }

    private Map<String, String> names(Collection<String> userIds) {
        var out = new LinkedHashMap<String, String>();
        contacts.contacts(new HashSet<>(userIds)).forEach((id, c) -> out.put(id, c.displayName()));
        return out;
    }

    private static Listings listing(ListingReadiness.@Nullable Counts c) {
        return c == null ? Listings.NONE : new Listings(c.total(), c.submitted(), c.live());
    }

    private static Listings menu(MenuReadiness.@Nullable Counts c) {
        return c == null ? Listings.NONE : new Listings(c.total(), c.submitted(), c.live());
    }

    /** A CSV field: quoted when it holds a comma, quote or line break; formula prefixes neutralised. */
    static String cell(@Nullable String value) {
        if (value == null) {
            return "";
        }
        var v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                ? "\"" + v.replace("\"", "\"\"") + "\""
                : v;
    }
}
