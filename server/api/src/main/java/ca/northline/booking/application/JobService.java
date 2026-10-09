package ca.northline.booking.application;

import ca.northline.booking.api.JobEscrows;
import ca.northline.booking.application.JobQueries.JobRow;
import ca.northline.booking.application.JobViews.Customer;
import ca.northline.booking.application.JobViews.JobDetail;
import ca.northline.booking.application.JobViews.JobSummary;
import ca.northline.booking.application.JobViews.TimelineEntry;
import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.Booking;
import ca.northline.booking.domain.BookingState;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.PersonDirectory.Person;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantRole;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jobs and the job flow. Transitions save the state, append the proof rows and publish the event in one transaction
 * (the Modulith registry is the outbox).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class JobService implements ListJobs, ViewJob, AdvanceJob, RequestApproval {

    static final String PHOTO_NOT_FOUND = "This photo wasn't uploaded to this business.";

    private final JobQueries queries;
    private final JobEscrows escrows;
    private final BookingRepository bookings;
    private final MediaCatalog media;
    private final PersonDirectory people;
    private final AccessNotes access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<JobSummary> list(ListJobs.Query query) {
        var rows = queries.jobs(query.viewer().merchantId(), query.from(), query.to(), ownOnly(query.viewer()));
        var names = people.people(rows.stream()
                .flatMap(r -> Stream.of(r.customerId(), r.memberUserId()))
                .filter(Objects::nonNull)
                .toList());
        return rows.stream().map(r -> summary(r, names)).toList();
    }

    @Override
    public JobDetail view(CurrentMember viewer, String bookingId) {
        var card = queries.card(viewer.merchantId(), bookingId)
                .filter(c -> visible(viewer, c.job().memberUserId()))
                .orElseThrow(() -> new NotFound("job", bookingId));
        var job = card.job();
        var log = queries.log(bookingId);
        var ids = new HashSet<String>();
        if (job.customerId() != null) {
            ids.add(job.customerId());
        }
        if (job.memberUserId() != null) {
            ids.add(job.memberUserId());
        }
        log.forEach(l -> ids.add(l.actorId()));
        var names = people.people(ids);
        var money = card.paid()
                ? escrows.of(viewer.merchantId(), List.of(bookingId)).get(bookingId)
                : null;
        var customerPerson = job.customerId() == null ? null : names.get(job.customerId());
        var customer = customerPerson == null
                ? null
                : new Customer(
                        customerPerson.displayName(),
                        customerPerson.reliabilityScore(),
                        queries.pastJobs(viewer.merchantId(), customerPerson.id(), job.startsAt()));
        return new JobDetail(
                job.id(),
                job.ref(),
                title(job),
                job.startsAt(),
                job.endsAt(),
                job.state(),
                job.memberUserId(),
                firstName(names, job.memberUserId()),
                customer,
                card.addressLine(),
                job.area(),
                access.forProvider(job.id(), card.access(), job.startsAt(), clock.instant()),
                card.vehicle(),
                card.customerNote(),
                job.priceCents(),
                money == null ? escrow(card.paid(), job.state()) : money.state(),
                money,
                log.stream()
                        .map(l -> new TimelineEntry(
                                l.type(), l.at(), firstName(names, l.actorId()), l.note(), l.mediaId()))
                        .toList(),
                queries.approvals(bookingId));
    }

    @Override
    @Transactional
    public JobDetail advance(AdvanceJob.Command command) {
        var booking = load(command.actor(), command.bookingId());
        var at = clock.instant();
        var actor = command.actor().userId();
        var progress = switch (command.step()) {
            case START_TRAVEL -> booking.startTravel(actor, at, command.point());
            case CHECK_IN -> booking.checkIn(actor, at, command.point());
            case COMPLETE -> {
                requirePhotos(command.actor().merchantId(), command.photoMediaIds());
                yield booking.complete(actor, at, command.point(), command.photoMediaIds(), command.report());
            }
        };
        bookings.save(booking);
        bookings.append(progress.log());
        events.publishEvent(progress.event());
        return view(command.actor(), booking.getId());
    }

    @Override
    @Transactional
    public Approval request(RequestApproval.Command command) {
        var booking = load(command.actor(), command.bookingId());
        var change = booking.requestApproval(
                command.description(), command.amountCents(), command.actor().userId(), clock.instant());
        bookings.save(booking);
        bookings.insert(change.approval());
        bookings.append(List.of(change.log()));
        events.publishEvent(change.event());
        return change.approval();
    }

    private Booking load(CurrentMember actor, String bookingId) {
        return bookings.find(actor.merchantId(), bookingId)
                .filter(b -> visible(actor, b.getMemberUserId()))
                .orElseThrow(() -> new NotFound("job", bookingId));
    }

    private void requirePhotos(String merchantId, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var found = media.find(merchantId, ids).stream()
                .map(MediaCatalog.MediaInfo::id)
                .toList();
        for (int i = 0; i < ids.size(); i++) {
            if (!found.contains(ids.get(i))) {
                throw RuleViolation.of("photoMediaIds[%d]".formatted(i), "not_found", PHOTO_NOT_FOUND);
            }
        }
    }

    /** Technicians work their own jobs only ("Own jobs, messages, complete &amp; photo"). */
    private static @Nullable String ownOnly(CurrentMember viewer) {
        return viewer.role() == MerchantRole.TECHNICIAN ? viewer.userId() : null;
    }

    private static boolean visible(CurrentMember viewer, @Nullable String memberUserId) {
        return viewer.role() != MerchantRole.TECHNICIAN || viewer.userId().equals(memberUserId);
    }

    private static JobSummary summary(JobRow r, Map<String, Person> names) {
        var customer = r.customerId() == null ? null : names.get(r.customerId());
        return new JobSummary(
                r.id(),
                r.ref(),
                title(r),
                r.startsAt(),
                r.endsAt(),
                r.state(),
                r.memberUserId(),
                firstName(names, r.memberUserId()),
                customer == null ? null : customer.shortName(),
                r.area(),
                r.priceCents());
    }

    private static String title(JobRow r) {
        return r.title() == null ? "Job" : r.title();
    }

    private static @Nullable String firstName(Map<String, Person> names, @Nullable String userId) {
        var person = userId == null ? null : names.get(userId);
        return person == null ? null : person.firstName();
    }

    /** Before payments has the escrow: "held" while the job runs and "released" after sign-off. */
    private static @Nullable String escrow(boolean paid, BookingState state) {
        if (!paid) {
            return null;
        }
        return switch (state) {
            case SIGNED_OFF -> "released";
            case CANCELLED -> null;
            default -> "held";
        };
    }
}
