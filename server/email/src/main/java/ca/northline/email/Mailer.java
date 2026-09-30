package ca.northline.email;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Sends one {@link EmailContent} to one recipient, at most once per {@link Delivery#key()}: renders it in the
 * recipient's language, adds the CASL footer and — for notifications — the unsubscribe link plus RFC 8058 one-click
 * headers, and hands it to the {@link EmailSender}. Meant for after-commit listeners and event consumers that may run
 * again for the same event.
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
     * @param unsubscribe one-click unsubscribe URL for {@link EmailContent.Purpose#NOTIFICATION} emails
     */
    record Delivery(
            String key,
            EmailAddress to,
            EmailContent content,
            Locale locale,
            @Nullable URI unsubscribe) {}

    enum Outcome {
        SENT,
        /** This key was sent (or rejected) before; nothing was sent now. */
        ALREADY_SENT,
        /** The provider refused this message for good (bad or suppressed address, content); logged, not retried. */
        REJECTED
    }
}
