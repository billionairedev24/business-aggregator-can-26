package ca.northline.email;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Sends one {@link EmailContent} to one recipient, at most once per {@link Delivery#key()}: renders it in the
 * recipient's language, adds the CASL footer and — for notifications — the unsubscribe link plus RFC 8058 one-click
 * headers, and hands it to the {@link EmailSender}. Meant for after-commit listeners and event consumers that may run
 * again for the same event. A commercial email (S-108) is sent only when {@link CommercialConsent} says the recipient
 * has consented — asked at send time, whenever the work was queued.
 */
public interface Mailer {

    /**
     * @throws EmailDeliveryFailed with {@link EmailDeliveryFailed.Kind#UNAVAILABLE} when the provider can't take mail
     *     even after the retries; the caller keeps the work (throws, so the event publication is retried later)
     */
    Outcome send(Delivery delivery);

    /**
     * @param key de-duplication key, unique per (event, recipient), e.g. {@code <eventId>:<userId>}
     * @param locale the recipient's language (the profile's {@code locale}); French for any {@code fr}
     * @param unsubscribe one-click unsubscribe URL for {@link EmailContent.Purpose#NOTIFICATION} and
     *     {@link EmailContent.Purpose#COMMERCIAL} emails
     * @param recipientId the recipient's user id: required for a commercial email (whose consent is checked)
     */
    record Delivery(
            String key,
            EmailAddress to,
            EmailContent content,
            Locale locale,
            @Nullable URI unsubscribe,
            @Nullable String recipientId) {

        /** A transactional or notification email (no consent to check). */
        public Delivery(String key, EmailAddress to, EmailContent content, Locale locale, @Nullable URI unsubscribe) {
            this(key, to, content, locale, unsubscribe, null);
        }
    }

    enum Outcome {
        SENT,
        /** A commercial email the recipient hasn't consented to (or withdrew from) by now: nothing was sent. */
        NO_CONSENT,
        /** This key was sent (or rejected) before; nothing was sent now. */
        ALREADY_SENT,
        /** The provider refused this message for good (bad or suppressed address, content); logged, not retried. */
        REJECTED
    }
}
