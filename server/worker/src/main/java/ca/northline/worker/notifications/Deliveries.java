package ca.northline.worker.notifications;

import ca.northline.email.EmailAddress;
import ca.northline.email.Mailer;
import ca.northline.email.UnsubscribeTokens;
import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.sms.SmsTransport;
import ca.northline.worker.events.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Sends one notice to one member on one channel, at most once: the claim {@code (channel, <eventId>:<userId>)} in
 * {@code events.processed_events} — the key the api's S-13 {@code Mailer} uses for email, so the two apps can never
 * both send — is committed on its own before the provider is called. Permanent refusals (bad number, rejected
 * address) keep the claim and are logged; an unavailable provider releases it and throws, so the Kafka retry (or the
 * deferred-notification job) sends it later.
 */
@Slf4j
public final class Deliveries {

    public static final String SENT = "northline.notifications.sent";

    public enum Outcome {
        SENT,
        ALREADY_SENT,
        UNREACHABLE
    }

    private final Mailer mailer;
    private final SmsTransport sms;
    private final PushSender push;
    private final ProcessedEvents claims;
    private final TransactionOperations separately;
    private final NotificationProperties links;
    private final UnsubscribeTokens unsubscribe;
    private final MeterRegistry meters;

    public Deliveries(
            Mailer mailer,
            SmsTransport sms,
            PushSender push,
            ProcessedEvents claims,
            TransactionOperations separately,
            NotificationProperties links,
            UnsubscribeTokens unsubscribe,
            MeterRegistry meters) {
        this.mailer = mailer;
        this.sms = sms;
        this.push = push;
        this.claims = claims;
        this.separately = separately;
        this.links = links;
        this.unsubscribe = unsubscribe;
        this.meters = meters;
    }

    public Outcome deliver(Notice notice, Recipient to, Channel channel, String business) {
        var key = notice.eventId() + ":" + to.userId();
        var outcome = switch (channel) {
            case EMAIL -> email(notice, to, business, key);
            case SMS -> sms(notice, to, business, key);
            case PUSH -> push(notice, to, business, key);
        };
        meters.counter(
                        SENT,
                        "channel",
                        channel.code(),
                        "type",
                        notice.type(),
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment();
        return outcome;
    }

    private Outcome email(Notice notice, Recipient to, String business, String key) {
        var content = notice.texts().email(business, links.studio(notice.merchantId(), "payouts"));
        if (content == null || to.email() == null || to.email().isBlank()) {
            return Outcome.UNREACHABLE;
        }
        EmailAddress address;
        try {
            address = new EmailAddress(to.email(), to.displayName());
        } catch (IllegalArgumentException e) {
            log.warn("Member {} has an unusable email address; {} not emailed", to.userId(), notice.eventId());
            return Outcome.UNREACHABLE;
        }
        var link = notice.row() == null
                ? null
                : links.unsubscribe(unsubscribe.issue(to.userId(), notice.row(), to.locale()));
        // The Mailer claims (email, key) itself — the same claim the api's S-13 notices use.
        return switch (mailer.send(new Mailer.Delivery(key, address, content, to.locale(), link))) {
            case SENT -> Outcome.SENT;
            case ALREADY_SENT -> Outcome.ALREADY_SENT;
            case REJECTED -> Outcome.UNREACHABLE;
        };
    }

    private Outcome sms(Notice notice, Recipient to, String business, String key) {
        var phone = to.phone();
        if (phone == null || !PhoneNumbers.isE164(phone)) {
            return Outcome.UNREACHABLE;
        }
        if (!claim(Channel.SMS, key)) {
            return Outcome.ALREADY_SENT;
        }
        try {
            sms.sendText(phone, notice.texts().text(business, to.locale()));
            return Outcome.SENT;
        } catch (SmsDeliveryFailed e) {
            if (e.getKind() == SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER) {
                log.warn(
                        "SMS {} to {} refused for good ({}): {}",
                        notice.type(),
                        PhoneNumbers.masked(phone),
                        key,
                        e.getMessage());
                return Outcome.UNREACHABLE; // the claim stays: not retried
            }
            release(Channel.SMS, key);
            throw e;
        } catch (RuntimeException e) {
            release(Channel.SMS, key);
            throw e;
        }
    }

    private Outcome push(Notice notice, Recipient to, String business, String key) {
        if (!claim(Channel.PUSH, key)) {
            return Outcome.ALREADY_SENT;
        }
        try {
            push.send(
                    to.userId(),
                    notice.texts().title(business, to.locale()),
                    notice.texts().text(business, to.locale()));
            return Outcome.SENT;
        } catch (RuntimeException e) {
            release(Channel.PUSH, key);
            throw e;
        }
    }

    private boolean claim(Channel channel, String key) {
        return Boolean.TRUE.equals(separately.execute(_ -> claims.claim(channel.code(), key)));
    }

    private void release(Channel channel, String key) {
        separately.executeWithoutResult(_ -> claims.release(channel.code(), key));
    }
}
