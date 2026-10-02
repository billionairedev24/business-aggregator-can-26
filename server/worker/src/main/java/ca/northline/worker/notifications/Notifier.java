package ca.northline.worker.notifications;

import ca.northline.email.EmailDeliveryFailed;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.worker.events.EventEnvelope;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Turns one domain event into notifications: the team's {@link Notice} for its type (S-27) and the customer's or
 * courier's (S-102, {@link PersonalNotices}); for each person and channel the worker owns — unless their matrix cell is
 * off (security and courier notices ignore the matrix) — a delivery now, or, for push and SMS inside their quiet hours,
 * a deferred one at the end of the quiet hours (security and courier notices are never held). Runs inside the consumer
 * framework's transaction; each delivery is claimed separately, so a retry after a provider outage sends only what is
 * missing. A team notice's outage is thrown (the event's retry topics); a customer's or courier's is deferred (their
 * consumer group has none) and the deferred job retries it.
 */
@Slf4j
public final class Notifier {

    static final Duration DEFERRED_RETRY = Duration.ofMinutes(5);
    static final int DEFERRED_ATTEMPTS = 10;

    private final Recipients recipients;
    private final Deliveries deliveries;
    private final DeferredNotifications deferred;
    private final PersonalNotices personal;
    private final Clock clock;

    public Notifier(
            Recipients recipients,
            Deliveries deliveries,
            DeferredNotifications deferred,
            PersonalNotices personal,
            Clock clock) {
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.deferred = deferred;
        this.personal = personal;
        this.clock = clock;
    }

    /** The {@code notifications} consumer's handler. */
    public void on(EventEnvelope event) {
        var notices = notices(event.id(), event.type(), event.version(), event.data());
        if (notices.isEmpty()) {
            log.debug("{} v{} is not a notification", event.type(), event.version());
            return;
        }
        RuntimeException outage = null;
        for (var notice : notices) {
            try {
                notify(notice);
            } catch (SmsDeliveryFailed | EmailDeliveryFailed | PushDeliveryFailed e) {
                outage = e; // the other audiences first; what was sent stays claimed
            }
        }
        if (outage != null) {
            throw outage;
        }
    }

    /** Every notice an event makes: the business's team's and the customer's or courier's. */
    List<Notice> notices(String eventId, String type, int version, JsonNode data) {
        var all = new ArrayList<Notice>();
        Notices.of(eventId, type, version, data).ifPresent(all::add);
        all.addAll(personal.of(eventId, type, version, data));
        return all;
    }

    /**
     * Sends one notice to its audience, on the channels the worker owns — for an event, or for something the worker
     * itself noticed (S-33: a webhook endpoint turned off; S-102: a booking tomorrow). Throws the provider outage after
     * the other people were served; what was sent stays claimed.
     */
    public void notify(Notice notice) {
        var business = business(notice);
        RuntimeException outage = null;
        for (var person : people(notice.audience())) {
            for (var channel : Channel.values()) {
                if (!notice.channels().contains(channel)) {
                    continue;
                }
                if (!notice.wantedBy(person, channel)) {
                    continue;
                }
                var now = clock.instant();
                if (!notice.security()
                        && channel.quietable()
                        && person.preferences().quietAt(now)) {
                    deferred.defer(
                            UlidCreator.getMonotonicUlid().toString(),
                            notice,
                            person,
                            channel,
                            person.preferences().quietEndsAfter(now));
                    continue;
                }
                var failure = attempt(notice, person, channel, business);
                if (failure == null) {
                    continue;
                }
                if (notice.audience() instanceof Notice.Audience.Team) {
                    outage = failure; // the other members first; the event's retry topics send it later
                } else {
                    // customers and couriers: retried from the table (their consumer group has no retry topics)
                    var asked = failure instanceof PushDeliveryFailed push ? push.retryAfter() : null;
                    var wait = asked != null ? asked : DEFERRED_RETRY;
                    deferred.defer(UlidCreator.getMonotonicUlid().toString(), notice, person, channel, now.plus(wait));
                }
            }
        }
        if (outage != null) {
            throw outage;
        }
    }

    /**
     * Sends the due deferred notifications (the {@code DeferredNotificationsJob}, every minute, in one transaction):
     * re-reads the person and their preferences — a cell turned off meanwhile cancels it — and retries an unavailable
     * provider every 5 minutes, giving up after 10 attempts with an ERROR (the alert, like a DLQ record).
     */
    public int sendDue(int limit) {
        var sent = 0;
        var now = clock.instant();
        for (var row : deferred.lockDue(now, limit)) {
            var notice = notices(row.eventId(), row.eventType(), row.eventVersion(), row.payload()).stream()
                    .filter(n -> DeferredNotifications.audience(n.audience()).equals(row.audience()))
                    .findFirst()
                    .orElse(null);
            var person = notice == null ? null : person(notice.audience(), row.userId());
            if (notice == null || person == null || !notice.wantedBy(person, row.channel())) {
                deferred.done(row.id());
                continue;
            }
            var failure = attempt(notice, person, row.channel(), business(notice));
            if (failure == null) {
                deferred.done(row.id());
                sent++;
            } else if (row.attempts() + 1 >= DEFERRED_ATTEMPTS) {
                deferred.done(row.id());
                log.error(
                        "DEAD-LETTERED deferred {} {} for user {} after {} attempts: {} — see"
                                + " docs/runbooks/notifications.md",
                        row.channel().code(),
                        row.eventType(),
                        row.userId(),
                        DEFERRED_ATTEMPTS,
                        failure.toString());
            } else {
                deferred.retryAt(row.id(), now.plus(DEFERRED_RETRY));
            }
        }
        return sent;
    }

    private List<Recipient> people(Notice.Audience audience) {
        return switch (audience) {
            case Notice.Audience.Team team -> recipients.of(team.merchantId(), team.roles());
            case Notice.Audience.Customer customer ->
                recipients.customer(customer.userId()).stream().toList();
            case Notice.Audience.Courier courier ->
                recipients.courier(courier.userId()).stream().toList();
        };
    }

    /** The person again (a deferred notification); null when they left the team or the account isn't active. */
    private @Nullable Recipient person(Notice.Audience audience, String userId) {
        return switch (audience) {
            case Notice.Audience.Team team ->
                recipients.member(team.merchantId(), userId).orElse(null);
            case Notice.Audience.Customer customer ->
                customer.userId().equals(userId) ? recipients.customer(userId).orElse(null) : null;
            case Notice.Audience.Courier courier ->
                courier.userId().equals(userId) ? recipients.courier(userId).orElse(null) : null;
        };
    }

    private String business(Notice notice) {
        var merchant = notice.merchantId();
        return merchant == null
                ? "Northline"
                : recipients.businessName(merchant).orElse("Northline");
    }

    /** Delivers; returns the provider outage to retry later, or null. Permanent refusals are handled inside. */
    private @Nullable RuntimeException attempt(Notice notice, Recipient person, Channel channel, String business) {
        try {
            deliveries.deliver(notice, person, channel, business);
            return null;
        } catch (SmsDeliveryFailed | EmailDeliveryFailed | PushDeliveryFailed e) {
            log.warn(
                    "{} {} to user {} not sent now: {}",
                    channel.code(),
                    notice.type(),
                    person.userId(),
                    e.getMessage());
            return e;
        }
    }
}
