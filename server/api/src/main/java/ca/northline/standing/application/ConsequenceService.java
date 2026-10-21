package ca.northline.standing.application;

import ca.northline.catalogue.api.InstantBook;
import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.CustomerNoShows;
import ca.northline.merchants.api.SellerSanctions;
import ca.northline.messaging.api.StandingNotice;
import ca.northline.payments.api.ReleaseDelays;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.standing.api.StaffLifts;
import ca.northline.standing.application.StandingUseCases.ConsequenceView;
import ca.northline.standing.domain.AppealRules;
import ca.northline.standing.domain.Consequence;
import ca.northline.standing.domain.ConsequenceKind;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consequence registry: records consequences as they happen and lifts them — with the owning module switching
 * back (merchants reinstates or shows in search, catalogue resumes instant book, payments lifts a release delay,
 * identity clears a customer's warning). Every staff lift is audited; the business is told by the owning module's
 * own notice (S-82's seller-oversight email) or by a {@link StandingNotice}.
 */
@Service
@RequiredArgsConstructor
@Transactional
class ConsequenceService implements StaffLifts {

    private final ConsequenceStore store;
    private final SellerSanctions sanctions;
    private final InstantBook instantBook;
    private final ReleaseDelays delays;
    private final CustomerNoShows noShows;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Records a consequence that just happened; false when an active one of that kind already existed. */
    boolean open(Consequence consequence) {
        return store.insert(consequence);
    }

    /** Closes the active consequence of {@code kind} after its owner switched back (a listener, the nightly job). */
    void closed(String subjectId, ConsequenceKind kind, String by, String role, String reason) {
        store.active(subjectId, kind).ifPresent(c -> store.lift(c.lift(by, role, reason, clock.instant())));
    }

    /**
     * Staff (or a granted appeal) lift a consequence: the owner switches back first, then the row is closed and the
     * platform audit log records it ({@code standing.consequence_lifted}).
     */
    ConsequenceView lift(String consequenceId, String reason, String staffId, String role, @Nullable String appeal) {
        var why = AppealRules.reason(reason);
        var c = store.find(consequenceId).orElseThrow(() -> new NotFound("consequence", consequenceId));
        if (!c.active()) {
            throw new Conflict("consequence_lifted", AppealRules.LIFTED);
        }
        switchBack(c, why, staffId, role);
        var lifted = c.lift(staffId, role, why, clock.instant());
        store.lift(lifted);
        audit.record(new AuditTrail.Entry(
                c.merchantId(),
                staffId,
                role,
                "standing.consequence_lifted",
                "consequence",
                c.id(),
                Map.of("kind", c.kind().code(), "cause", c.cause()),
                appeal == null ? Map.of() : Map.of("appealId", appeal)));
        return StandingViewService.view(lifted, null, clock.instant());
    }

    private void switchBack(Consequence c, String reason, String staffId, String role) {
        switch (c.kind()) {
            case SUSPENDED -> sanctions.reinstate(Objects.requireNonNull(c.merchantId()), reason, staffId, role);
            case SEARCH_HIDDEN -> sanctions.showInSearch(Objects.requireNonNull(c.merchantId()), reason, staffId, role);
            case INSTANT_BOOK_OFF -> {
                var merchantId = Objects.requireNonNull(c.merchantId());
                if (instantBook.resume(merchantId, null, staffId)) {
                    events.publishEvent(notice(c, "instant_book_resumed", reason));
                }
            }
            case PHOTO_DELAY -> delays.lift(Objects.requireNonNull(c.refId()), staffId);
            case NO_SHOW_WARNING -> noShows.lift(Objects.requireNonNull(c.customerId()));
            default -> throw new IllegalStateException("No switch to turn back for " + c.kind());
        }
    }

    StandingNotice notice(Consequence c, String change, String reason) {
        return new StandingNotice(
                Ids.next(),
                clock.instant(),
                c.id(),
                Objects.requireNonNull(c.merchantId()),
                change,
                c.kind().code(),
                reason,
                null,
                0);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> liftedWithin(String kind, Duration within) {
        return store.liftedByStaffSince(
                ConsequenceKind.valueOf(kind.toUpperCase(Locale.ROOT)),
                clock.instant().minus(within));
    }
}
