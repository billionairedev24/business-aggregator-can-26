package ca.northline.standing.application;

import ca.northline.catalogue.api.InstantBook;
import ca.northline.identity.api.CustomerNoShows;
import ca.northline.shared.Ids;
import ca.northline.standing.application.StandingUseCases.EnforceStanding;
import ca.northline.standing.domain.Consequence;
import ca.northline.standing.domain.ConsequenceKind;
import ca.northline.trust.api.TrustConsequences;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link EnforceStanding}: the S-93 rules S-82 couldn't enforce (no state to switch), nightly after the quality scores.
 * Trust counts the confirmed no-shows; catalogue and identity switch; the registry records it with the reason.
 *
 * <ul>
 *   <li><b>Provider no-shows</b> ({@code provider_no_shows}, default 3 in 30 days): instant book is paused; once the
 *       window holds fewer than the count it is resumed by itself.
 *   <li><b>Customer no-shows</b> ({@code customer_no_shows}, default 2, over 12 months): providers see the count and
 *       the customer can't book instantly; cleared by itself once fewer than the count remain in 12 months.
 *   <li>Photo delays whose time ran out are closed (the escrow is released by payments' own job).
 * </ul>
 *
 * A consequence staff lifted (by hand or by granting an appeal) isn't put back within the rule's window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class StandingEnforcementService implements EnforceStanding {

    /** "Customer no-show ×2" counts over the customer's reliability window (12 months, the account page's copy). */
    static final Duration CUSTOMER_WINDOW = Duration.ofDays(365);

    static final String PROVIDER_NO_SHOWS = "provider_no_shows";
    static final String CUSTOMER_NO_SHOWS = "customer_no_shows";

    private final TrustConsequences trust;
    private final InstantBook instantBook;
    private final CustomerNoShows noShows;
    private final ConsequenceStore store;
    private final ConsequenceService consequences;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Result run() {
        var now = clock.instant();
        var rule = trust.providerNoShowRule();
        var window = Duration.ofDays(rule.days());
        var counts = trust.providerNoShows();
        var exempt = store.liftedByStaffSince(ConsequenceKind.INSTANT_BOOK_OFF, now.minus(window));
        int paused = 0;
        int resumed = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() < rule.count() || exempt.contains(e.getKey())) {
                continue;
            }
            var reason = ("%d confirmed no-shows in the last %d days (Northline's rules: %d → instant book off)."
                            + " Customers ask for a quote first; instant book comes back once the last %d days hold"
                            + " fewer than %d.")
                    .formatted(e.getValue(), rule.days(), rule.count(), rule.days(), rule.count());
            if (instantBook.pause(e.getKey(), PROVIDER_NO_SHOWS, reason, Consequence.SYSTEM)) {
                var c = Consequence.ofBusiness(
                        Ids.next(),
                        e.getKey(),
                        ConsequenceKind.INSTANT_BOOK_OFF,
                        PROVIDER_NO_SHOWS,
                        reason,
                        null,
                        Map.of("noShows", e.getValue().toString(), "days", Integer.toString(rule.days())),
                        Consequence.SYSTEM,
                        Consequence.SYSTEM,
                        now,
                        null);
                consequences.open(c);
                events.publishEvent(consequences.notice(c, "instant_book_paused", reason));
                paused++;
            }
        }
        for (var merchantId : instantBook.pausedFor(PROVIDER_NO_SHOWS)) {
            if (counts.getOrDefault(merchantId, 0) >= rule.count()) {
                continue;
            }
            var reason = "Fewer than %d confirmed no-shows in the last %d days.".formatted(rule.count(), rule.days());
            if (instantBook.resume(merchantId, PROVIDER_NO_SHOWS, Consequence.SYSTEM)) {
                store.active(merchantId, ConsequenceKind.INSTANT_BOOK_OFF).ifPresent(c -> {
                    store.lift(c.lift(Consequence.SYSTEM, Consequence.SYSTEM, reason, now));
                    events.publishEvent(consequences.notice(c, "instant_book_resumed", reason));
                });
                resumed++;
            }
        }
        var limit = trust.customerNoShowLimit();
        var customers = trust.customerNoShows(CUSTOMER_WINDOW);
        var cleared = store.liftedByStaffSince(ConsequenceKind.NO_SHOW_WARNING, now.minus(CUSTOMER_WINDOW));
        int warned = 0;
        int lifted = 0;
        for (var e : customers.entrySet()) {
            if (e.getValue() < limit || cleared.contains(e.getKey())) {
                continue;
            }
            noShows.restrict(e.getKey(), e.getValue(), now);
            var reason = ("%d confirmed no-shows in the last 12 months. Providers see this before they accept, and"
                            + " they confirm your bookings first.")
                    .formatted(e.getValue());
            if (consequences.open(Consequence.ofCustomer(
                    Ids.next(),
                    e.getKey(),
                    ConsequenceKind.NO_SHOW_WARNING,
                    CUSTOMER_NO_SHOWS,
                    reason,
                    Map.of("noShows", e.getValue().toString()),
                    now))) {
                warned++;
            }
        }
        for (var userId : noShows.restricted()) {
            if (customers.getOrDefault(userId, 0) >= limit) {
                continue;
            }
            if (noShows.lift(userId)) {
                consequences.closed(
                        userId,
                        ConsequenceKind.NO_SHOW_WARNING,
                        Consequence.SYSTEM,
                        Consequence.SYSTEM,
                        "Fewer than %d confirmed no-shows in the last 12 months.".formatted(limit));
                lifted++;
            }
        }
        for (var c : store.activeEndedBefore(ConsequenceKind.PHOTO_DELAY, now, 500)) {
            store.lift(c.lift(Consequence.SYSTEM, Consequence.SYSTEM, "The delay ended.", now));
        }
        var result = new Result(paused, resumed, warned, lifted);
        if (paused + resumed + warned + lifted > 0) {
            log.info(
                    "Standing rules enforced: instant book paused {} / resumed {}, customers warned {} / cleared {}",
                    paused,
                    resumed,
                    warned,
                    lifted);
        }
        return result;
    }
}
