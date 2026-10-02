package ca.northline.messaging.application;

import java.util.Locale;
import java.util.Optional;

/**
 * The link in a notification email's footer (and its RFC 8058 one-click POST): turns that notification's email cell
 * off in the member's Settings › Notifications matrix. For a commercial message (S-108) — token row
 * {@code consent.<category>}, or a customer's {@code customer.offers} — it withdraws the CASL consent at once. An
 * invalid token is empty.
 */
public interface UnsubscribeFromEmails {

    /** Token rows that withdraw a consent: {@code consent.marketing_email}, {@code consent.marketing_sms}, … */
    String CONSENT = "consent.";

    /** What the token would turn off (the confirmation page); nothing changes. */
    Optional<Subscription> check(String token);

    /**
     * Turns the email off or withdraws the consent (idempotent), now.
     *
     * @param oneClick a mailbox provider's RFC 8058 one-click POST, rather than the person on the page
     */
    Optional<Subscription> unsubscribe(String token, boolean oneClick);

    /** @param event a Settings › Notifications row, e.g. {@code payout}, {@code customer.order_updates}, or a consent */
    record Subscription(String userId, String event, Locale locale) {

        /** {@code sms} for a commercial SMS's opt-out link, else {@code email}. */
        public String channel() {
            return event.equals(CONSENT + "marketing_sms") ? "sms" : "email";
        }

        /** Where the reader manages these: {@code account} (customers, consents) or {@code studio}. */
        public String settingsPlace() {
            return event.startsWith("customer.") || event.startsWith(CONSENT) ? "account" : "studio";
        }
    }
}
