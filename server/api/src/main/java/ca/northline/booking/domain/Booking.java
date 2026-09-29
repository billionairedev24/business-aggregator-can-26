package ca.northline.booking.domain;

import ca.northline.booking.api.BookingProgressed;
import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.booking.api.BookingProgressed.BookingEnRoute;
import ca.northline.booking.api.BookingProgressed.BookingOnSite;
import ca.northline.booking.api.BookingProgressed.BookingScopeChangeRequested;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A job (aggregate root) and its state machine: confirmed → en route → on site → completed → signed off. Each
 * transition returns the append-only log rows (with GPS / photo proof) and the event to publish in the same
 * transaction. Sign-off belongs to the customer; disputes and cancellation are handled elsewhere.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Booking {

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private final @Nullable String memberUserId;
    private final @Nullable String customerId;

    @ToString.Include
    private BookingState state;

    private Instant updatedAt;
    private final long version;

    /** The rows to append and the event to publish for one transition. */
    public record Progress(List<BookingLogEntry> log, BookingProgressed event) {
        public Progress {
            log = List.copyOf(log);
        }
    }

    /** A scope-change request: the approval row, its log row and the event. */
    public record ScopeChange(Approval approval, BookingLogEntry log, BookingScopeChangeRequested event) {}

    public Progress startTravel(String actorId, Instant at, @Nullable GeoPoint point) {
        move(BookingState.CONFIRMED, BookingState.EN_ROUTE, at);
        return new Progress(
                List.of(entry(BookingState.EN_ROUTE.code(), actorId, at, point, null, null)),
                new BookingEnRoute(Ids.next(), at, id, merchantId, actorId));
    }

    public Progress checkIn(String actorId, Instant at, @Nullable GeoPoint point) {
        move(BookingState.EN_ROUTE, BookingState.ON_SITE, at);
        return new Progress(
                List.of(entry(BookingState.ON_SITE.code(), actorId, at, point, null, null)),
                new BookingOnSite(Ids.next(), at, id, merchantId, actorId));
    }

    /**
     * Completes the job with its photos and written report. Photos are optional — without them the escrow releases 48 h
     * later (quality score "completion photos").
     */
    public Progress complete(
            String actorId, Instant at, @Nullable GeoPoint point, List<String> photoMediaIds, @Nullable String report) {
        move(BookingState.ON_SITE, BookingState.COMPLETED, at);
        var log = new ArrayList<BookingLogEntry>();
        log.add(entry(
                BookingState.COMPLETED.code(),
                actorId,
                at,
                point,
                photoMediaIds.isEmpty() ? null : photoMediaIds.getFirst(),
                blankToNull(report)));
        photoMediaIds.stream()
                .skip(1)
                .forEach(media -> log.add(entry(BookingLogEntry.PHOTO, actorId, at, null, media, null)));
        return new Progress(log, new BookingCompleted(Ids.next(), at, id, merchantId, actorId, photoMediaIds.size()));
    }

    /** "Request extra parts approval" — allowed while the job is confirmed, en route or on site. */
    public ScopeChange requestApproval(String description, long amountCents, String actorId, Instant at) {
        if (!state.isActive()) {
            throw new Conflict(
                    "job_state",
                    "This job is %s; extra approvals are only possible before it is completed."
                            .formatted(state.code().replace('_', ' ')));
        }
        var approval = Approval.request(id, description, amountCents, actorId, at);
        updatedAt = at;
        return new ScopeChange(
                approval,
                entry(BookingLogEntry.APPROVAL_REQUESTED, actorId, at, null, null, approval.description()),
                new BookingScopeChangeRequested(
                        Ids.next(), at, id, merchantId, actorId, approval.id(), approval.amountCents()));
    }

    /** Customer sign-off (consumer app) — releases escrow. */
    public BookingLogEntry signOff(String customerId, Instant at) {
        if (!customerId.equals(this.customerId)) {
            throw new Conflict("not_customer", "Only the customer can sign off this job.");
        }
        move(BookingState.COMPLETED, BookingState.SIGNED_OFF, at);
        return entry(BookingState.SIGNED_OFF.code(), customerId, at, null, null, null);
    }

    private void move(BookingState from, BookingState to, Instant at) {
        if (state != from) {
            throw new Conflict(
                    "job_state",
                    "This job is %s and can't move to %s."
                            .formatted(state.code().replace('_', ' '), to.code().replace('_', ' ')));
        }
        state = to;
        updatedAt = at;
    }

    private BookingLogEntry entry(
            String type,
            String actorId,
            Instant at,
            @Nullable GeoPoint point,
            @Nullable String mediaId,
            @Nullable String note) {
        return new BookingLogEntry(Ids.next(), id, type, at, actorId, point, mediaId, note);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
