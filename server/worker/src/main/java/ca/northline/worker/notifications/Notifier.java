package ca.northline.worker.notifications;

import ca.northline.email.EmailDeliveryFailed;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.worker.events.EventEnvelope;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Turns one domain event into notifications (S-27): the {@link Notice} for its type, the business's members in the
 * notice's roles, and for each member and channel the worker owns — unless their Settings › Notifications cell is off
 * (security notices ignore the matrix) — a delivery now, or, for push and SMS inside their quiet hours, a deferred one
 * at the end of the quiet hours (security notices are never held). Runs inside the consumer framework's transaction;
 * each delivery is claimed separately, so a retry after a provider outage sends only what is missing.
 */
@Slf4j
public final class Notifier {

    static final Duration DEFERRED_RETRY = Duration.ofMinutes(5);
    static final int DEFERRED_ATTEMPTS = 10;

    private final Recipients recipients;
    private final Deliveries deliveries;
    private final DeferredNotifications deferred;
    private final Clock clock;

    public Notifier(Recipients recipients, Deliveries deliveries, DeferredNotifications deferred, Clock clock) {
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.deferred = deferred;
        this.clock = clock;
    }

    /** The {@code notifications} consumer's handler. */
    public void on(EventEnvelope event) {
        var notice = Notices.of(event.id(), event.type(), event.version(), event.data())
                .orElse(null);
        if (notice == null) {
            log.debug("{} v{} is not a notification", event.type(), event.version());
            return;
        }
        var business = recipients.businessName(notice.merchantId()).orElse("Northline");
        RuntimeException outage = null;
        for (var member : recipients.of(notice.merchantId(), notice.roles())) {
            for (var channel : Channel.values()) {
                if (!notice.channels().contains(channel)) {
                    continue;
                }
                if (!notice.wantedBy(member, channel)) {
                    continue;
                }
                var now = clock.instant();
                if (!notice.security()
                        && channel.quietable()
                        && member.preferences().quietAt(now)) {
                    deferred.defer(
                            UlidCreator.getMonotonicUlid().toString(),
                            notice,
                            member,
                            channel,
                            member.preferences().quietEndsAfter(now));
                    continue;
                }
                var failure = attempt(notice, member, channel, business);
                if (failure != null) {
                    outage = failure; // the other members first; what was sent stays claimed
                }
            }
        }
        if (outage != null) {
            throw outage;
        }
    }

    /**
     * Sends the due deferred notifications (the {@code DeferredNotificationsJob}, every minute, in one transaction):
     * re-reads the member and their preferences — a cell turned off meanwhile cancels it — and retries an unavailable
     * provider every 5 minutes, giving up after 10 attempts with an ERROR (the alert, like a DLQ record).
     */
    public int sendDue(int limit) {
        var sent = 0;
        var now = clock.instant();
        for (var row : deferred.lockDue(now, limit)) {
            var notice = Notices.of(row.eventId(), row.eventType(), row.eventVersion(), row.payload())
                    .orElse(null);
            var member = recipients.member(row.merchantId(), row.userId()).orElse(null);
            if (notice == null || member == null || !notice.wantedBy(member, row.channel())) {
                deferred.done(row.id());
                continue;
            }
            var business = recipients.businessName(notice.merchantId()).orElse("Northline");
            var failure = attempt(notice, member, row.channel(), business);
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

    /** Delivers; returns the provider outage to retry later, or null. Permanent refusals are handled inside. */
    private @Nullable RuntimeException attempt(Notice notice, Recipient member, Channel channel, String business) {
        try {
            deliveries.deliver(notice, member, channel, business);
            return null;
        } catch (SmsDeliveryFailed | EmailDeliveryFailed e) {
            log.warn(
                    "{} {} to user {} not sent now: {}",
                    channel.code(),
                    notice.type(),
                    member.userId(),
                    e.getMessage());
            return e;
        }
    }
}
