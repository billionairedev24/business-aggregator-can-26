package ca.northline.availability.application;

import static ca.northline.availability.application.CalendarConnectionService.quietly;

import ca.northline.availability.api.AvailabilityChanged;
import ca.northline.availability.application.CalendarGateway.BookingEvent;
import ca.northline.availability.application.CalendarGateway.BusyEvent;
import ca.northline.availability.application.CalendarGateway.Changes;
import ca.northline.availability.application.CalendarGateway.CursorExpired;
import ca.northline.availability.application.CalendarGateway.EventGone;
import ca.northline.availability.application.CalendarGateway.GrantRevoked;
import ca.northline.availability.application.CalendarGateway.Unauthorized;
import ca.northline.availability.application.CalendarGateway.Watch;
import ca.northline.availability.application.CalendarLinkRepository.Link;
import ca.northline.availability.application.CalendarSyncRepository.Channel;
import ca.northline.availability.application.CalendarSyncRepository.Mirror;
import ca.northline.availability.application.CalendarSyncRepository.Source;
import ca.northline.availability.application.CalendarUseCases.CalendarJobs;
import ca.northline.availability.domain.BookingEventText;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.booking.api.BookingCalendar;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two directions of S-32 sync and the notification channels. Each unit of work (one calendar read, one member's
 * write-back, one channel) runs in its own transaction, so one failing provider call never undoes the others. S-136:
 * a read and a write-back lock the member's link before anything under it, like a disconnect does
 * ({@link CalendarLinkRepository#lockWaiting}).
 *
 * <ul>
 *   <li><b>Inbound:</b> busy events of every chosen calendar, incrementally (Google sync token / Graph delta link), on
 *       every verified notification and at least every {@code syncInterval} (safety net). Free, cancelled and declined
 *       events and Northline's own events never block. Only start, end and the provider's id are stored.
 *   <li><b>Outbound:</b> confirmed bookings of the member are written to their calendar, rewritten when the time,
 *       service or address changes, deleted when the booking is cancelled or reassigned. The text is {@link
 *       BookingEventText}. An event the member deleted comes back (Northline's bookings win).
 * </ul>
 */
@Slf4j
@Service
class CalendarSyncService implements CalendarJobs {

    static final Duration READ_BEHIND = Duration.ofDays(1);
    static final Duration WINDOW_SLIDE = Duration.ofDays(7);
    static final Duration RENEW_BEFORE = Duration.ofDays(1);
    static final Duration KEEP_NOTIFICATIONS = Duration.ofDays(7);
    static final Duration FORGET_PAST_MIRRORS = Duration.ofDays(30);
    static final int BATCH = 100;

    private final CalendarLinkRepository links;
    private final CalendarSyncRepository sync;
    private final CalendarGateways gateways;
    private final CalendarAccess access;
    private final CalendarSettings settings;
    private final BookingCalendar bookings;
    private final PersonDirectory people;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate own;
    private final Team team;

    CalendarSyncService(
            CalendarLinkRepository links,
            CalendarSyncRepository sync,
            CalendarGateways gateways,
            CalendarAccess access,
            CalendarSettings settings,
            BookingCalendar bookings,
            PersonDirectory people,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactions,
            Team team) {
        this.links = links;
        this.sync = sync;
        this.gateways = gateways;
        this.access = access;
        this.settings = settings;
        this.bookings = bookings;
        this.people = people;
        this.events = events;
        this.clock = clock;
        this.team = team;
        this.tx = new TransactionTemplate(transactions);
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── after connect ────────────────────────────────────────────────────────────

    /** First read of every chosen calendar, notification channels, and the member's bookings written. */
    void connected(String linkId) {
        for (var source : sync.sources(linkId)) {
            read(linkId, source.calendarId());
            watch(linkId, source.calendarId());
        }
        writeBack(linkId, true);
    }

    // ── inbound ──────────────────────────────────────────────────────────────────

    @Override
    public int syncDue() {
        var due = sync.due(clock.instant().minus(settings.syncInterval()), BATCH);
        var readBefore = clock.instant().minus(settings.syncInterval().dividedBy(2));
        return (int) due.stream()
                .filter(s -> safely("read " + s.calendarId(), () -> read(s.linkId(), s.calendarId(), readBefore)))
                .count();
    }

    /** Reads one calendar's changes now (a notification); false when skipped. */
    boolean read(String linkId, String calendarId) {
        return read(linkId, calendarId, null);
    }

    /**
     * Reads one calendar's changes; false when it was skipped: another replica is reading it, it was read after
     * {@code readBefore} meanwhile (jobs of two replicas), or the link is gone or needs a reconnect.
     *
     * <p>S-136: the link is locked first and waited for. The read ends by updating it ({@code last_sync_at}, and a
     * rotated refresh token or the reconnect state on the way), while a disconnect deletes it and then its sources. With
     * the source locked first, the two deadlocked.
     */
    boolean read(String linkId, String calendarId, @Nullable Instant readBefore) {
        return Boolean.TRUE.equals(tx.execute(_ -> {
            var link =
                    links.lockWaiting(linkId).filter(l -> !l.needsReconnect()).orElse(null);
            var source = link == null ? null : sync.lock(linkId, calendarId).orElse(null);
            if (link == null
                    || source == null
                    || (readBefore != null
                            && source.syncedAt() != null
                            && source.syncedAt().isAfter(readBefore))) {
                return false;
            }
            var now = clock.instant();
            var from = now.minus(READ_BEHIND);
            var to = now.plus(settings.readAhead());
            // Graph's delta window is fixed when the read starts from scratch: slide it weekly
            var cursor = source.windowFrom() != null && source.windowFrom().isBefore(from.minus(WINDOW_SLIDE))
                    ? null
                    : source.cursor();
            var gateway = gateways.get(link.provider());
            var zone = team.zone(link.merchantId());
            var changes = access.with(link, token -> {
                try {
                    return gateway.changes(token, calendarId, cursor, from, to, zone);
                } catch (CursorExpired _) {
                    return gateway.changes(token, calendarId, null, from, to, zone);
                }
            });
            if (changes.isEmpty()) {
                return false;
            }
            apply(link, source, changes.get(), from, to, now);
            return true;
        }));
    }

    private void apply(Link link, Source source, Changes changes, Instant from, Instant to, Instant now) {
        var ours = sync.mirroredEventIds(link.id());
        var busy = new ArrayList<BusyEvent>();
        var removed = new ArrayList<>(changes.removed());
        for (var event : changes.busy()) {
            if (ours.contains(event.id())
                    || !event.endsAt().isAfter(from)
                    || !event.startsAt().isBefore(to)) {
                removed.add(event.id());
            } else {
                busy.add(event);
            }
        }
        if (changes.full()) {
            sync.replaceBusy(link.id(), source.calendarId(), busy);
        } else {
            sync.removeBusy(link.id(), source.calendarId(), removed);
            sync.upsertBusy(link.id(), source.calendarId(), busy);
        }
        var windowFrom = changes.full() ? from : Objects.requireNonNullElse(source.windowFrom(), from);
        sync.saveCursor(link.id(), source.calendarId(), changes.cursor(), windowFrom, now);
        links.synced(link.id(), now);
        if (changes.full() || !busy.isEmpty() || !changes.removed().isEmpty()) {
            events.publishEvent(
                    new AvailabilityChanged(Ids.next(), now, link.merchantId(), link.memberUserId(), "calendar"));
        }
    }

    // ── outbound ─────────────────────────────────────────────────────────────────

    @Override
    public int writeBackDue() {
        return links.connected().stream()
                .mapToInt(l -> {
                    var n = new int[1];
                    safely("write back " + l.id(), () -> {
                        n[0] = writeBack(l.id());
                        return true;
                    });
                    return n[0];
                })
                .sum();
    }

    /** S-55: a booking was confirmed — write the member's calendars now instead of at the next 5-minute run. */
    public int writeBackMember(String merchantId, String memberUserId) {
        return links.connected().stream()
                .filter(l ->
                        l.merchantId().equals(merchantId) && l.memberUserId().equals(memberUserId))
                .mapToInt(l -> writeBack(l.id(), true))
                .sum();
    }

    /** The job's write-back: skips a link another replica (or a read) holds; it comes round again next run. */
    int writeBack(String linkId) {
        return writeBack(linkId, false);
    }

    /**
     * Writes, rewrites and deletes the member's booking events; returns the number of provider writes. {@code wait}:
     * wait for a read or disconnect that holds the link (S-136: reads lock it now) instead of skipping it, so a new
     * booking still reaches the calendar at once.
     */
    private int writeBack(String linkId, boolean wait) {
        return Objects.requireNonNullElse(
                tx.execute(_ -> {
                    var link = (wait ? links.lockWaiting(linkId) : links.lock(linkId))
                            .filter(l -> !l.needsReconnect())
                            .orElse(null);
                    if (link == null || link.writeCalendarId() == null) {
                        return 0;
                    }
                    return access.with(link, token -> writeBack(link, token)).orElse(0);
                }),
                0);
    }

    private int writeBack(Link link, String token) {
        var gateway = gateways.get(link.provider());
        var calendarId = Objects.requireNonNull(link.writeCalendarId());
        var now = clock.instant();
        var from = now.minus(READ_BEHIND);
        var jobs = bookings.jobs(link.merchantId(), link.memberUserId(), from, now.plus(settings.writeAhead()));
        var names = people.people(jobs.stream()
                .map(BookingCalendar.Job::customerId)
                .filter(Objects::nonNull)
                .toList());
        var mirrors = new HashMap<>(sync.mirrors(link.id()));
        var link2studio = settings.studioBase()
                .resolve("/b/" + link.merchantId() + "/appointments")
                .toString();
        var writes = 0;
        for (var job : jobs) {
            var person = job.customerId() == null ? null : names.get(job.customerId());
            var text = BookingEventText.of(
                    job.title(), person == null ? null : person.firstName(), job.addressLine(), job.ref(), link2studio);
            var hash = text.hash(job.startsAt(), job.endsAt());
            var event = new BookingEvent(
                    job.bookingId(), text.summary(), text.location(), text.description(), job.startsAt(), job.endsAt());
            var mirror = mirrors.remove(job.bookingId());
            if (mirror != null && mirror.contentHash().equals(hash)) {
                continue;
            }
            try {
                String eventId;
                String writtenTo;
                if (mirror == null) {
                    eventId = gateway.create(token, calendarId, event);
                    writtenTo = calendarId;
                } else {
                    try {
                        gateway.update(token, mirror.calendarId(), mirror.externalEventId(), event);
                        eventId = mirror.externalEventId();
                        writtenTo = mirror.calendarId();
                    } catch (EventGone _) {
                        eventId = gateway.create(token, calendarId, event);
                        writtenTo = calendarId;
                    }
                }
                sync.saveMirror(new Mirror(
                        link.id(), job.bookingId(), writtenTo, eventId, job.startsAt(), job.endsAt(), hash, now));
                writes++;
            } catch (GrantRevoked | Unauthorized e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn(
                        "Writing booking {} to calendar link {} failed: {}",
                        job.bookingId(),
                        link.id(),
                        e.getMessage());
            }
        }
        // left over: cancelled, reassigned or requested again — or simply past the window
        for (var mirror : mirrors.values()) {
            if (mirror.endsAt().isBefore(from)) {
                if (mirror.endsAt().isBefore(now.minus(FORGET_PAST_MIRRORS))) {
                    sync.deleteMirror(link.id(), mirror.bookingId());
                }
                continue;
            }
            try {
                gateway.delete(token, mirror.calendarId(), mirror.externalEventId());
                sync.deleteMirror(link.id(), mirror.bookingId());
                writes++;
            } catch (GrantRevoked | Unauthorized e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Deleting event of booking {} failed: {}", mirror.bookingId(), e.getMessage());
            }
        }
        return writes;
    }

    // ── notification channels ────────────────────────────────────────────────────

    /** Opens a channel for the source unless notifications are off (no HTTPS address) or one is open. */
    boolean watch(String linkId, String calendarId) {
        if (!settings.notificationsEnabled()) {
            return false;
        }
        var link = links.byId(linkId).filter(l -> !l.needsReconnect()).orElse(null);
        if (link == null
                || sync.channels(linkId).stream()
                        .anyMatch(c -> c.calendarId().equals(calendarId) && c.externalId() != null)) {
            return false;
        }
        var gateway = gateways.get(link.provider());
        var channelId = Ids.next().toLowerCase(Locale.ROOT);
        var secret = CalendarSecrets.random();
        var now = clock.instant();
        var until = now.plus(settings.channelTtl());
        own.executeWithoutResult(_ -> sync.insertChannel(new Channel(
                channelId, linkId, calendarId, link.provider(), null, CalendarSecrets.sha256(secret), until, now)));
        try {
            var subscription = access.with(
                    link,
                    token -> gateway.watch(new Watch(
                            token,
                            calendarId,
                            channelId,
                            secret,
                            settings.notificationUri(path(link.provider())),
                            until)));
            own.executeWithoutResult(_ -> subscription.ifPresentOrElse(
                    s -> sync.activateChannel(channelId, s.externalId(), s.expiresAt()),
                    () -> sync.deleteChannel(channelId)));
            return subscription.isPresent();
        } catch (RuntimeException e) {
            own.executeWithoutResult(_ -> sync.deleteChannel(channelId));
            log.warn(
                    "Opening a {} notification channel failed (periodic reads continue): {}",
                    link.provider().code(),
                    e.getMessage());
            return false;
        }
    }

    @Override
    public int renewChannels() {
        var now = clock.instant();
        var renewed = 0;
        for (var channel : sync.expiringBefore(now.plus(RENEW_BEFORE), BATCH)) {
            if (safely("renew " + channel.id(), () -> renew(channel, false))) {
                renewed++;
            }
        }
        if (settings.notificationsEnabled()) {
            for (var link : links.connected()) {
                for (var source : sync.sources(link.id())) {
                    if (safely("watch " + source.calendarId(), () -> watch(link.id(), source.calendarId()))) {
                        renewed++;
                    }
                }
            }
        }
        return renewed;
    }

    /** Extends a channel (Graph) or replaces it (Google; {@code recreate} for a removed Graph subscription). */
    boolean renew(String channelId, boolean recreate) {
        return sync.channel(channelId).map(c -> renew(c, recreate)).orElse(false);
    }

    private boolean renew(Channel channel, boolean recreate) {
        var link = links.byId(channel.linkId()).filter(l -> !l.needsReconnect()).orElse(null);
        var now = clock.instant();
        if (link == null || channel.externalId() == null) {
            if (link == null || channel.createdAt().isBefore(now.minus(Duration.ofMinutes(5)))) {
                own.executeWithoutResult(_ -> sync.deleteChannel(channel.id()));
            }
            return false;
        }
        var gateway = gateways.get(link.provider());
        var externalId = channel.externalId();
        if (!recreate) {
            var extended = access.with(
                    link, token -> gateway.renew(token, channel.id(), externalId, now.plus(settings.channelTtl())));
            if (extended.isEmpty()) {
                return false;
            }
            if (extended.get().isPresent()) {
                var s = extended.get().get();
                own.executeWithoutResult(_ -> sync.activateChannel(channel.id(), s.externalId(), s.expiresAt()));
                return true;
            }
        }
        // replace: stop the old one at the provider, then open a new one
        access.with(link, token -> {
            quietly(() -> gateway.unwatch(token, channel.id(), externalId));
            return true;
        });
        own.executeWithoutResult(_ -> sync.deleteChannel(channel.id()));
        return watch(link.id(), channel.calendarId());
    }

    @Override
    public int purge() {
        var now = clock.instant();
        return Objects.requireNonNullElse(
                tx.execute(_ -> sync.purgeRequests(now) + sync.purgeNotifications(now.minus(KEEP_NOTIFICATIONS))), 0);
    }

    static String path(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? "google" : "microsoft";
    }

    private static boolean safely(String what, BooleanSupplier work) {
        try {
            return work.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("Calendar sync step '{}' failed; retrying next run: {}", what, e.getMessage());
            return false;
        }
    }
}
