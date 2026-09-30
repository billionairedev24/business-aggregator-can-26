package ca.northline.messaging.application;

import java.util.Locale;
import java.util.Optional;

/**
 * The link in a notification email's footer (and its RFC 8058 one-click POST): turns that notification's email cell
 * off in the member's Settings › Notifications matrix. An invalid token is empty.
 */
public interface UnsubscribeFromEmails {

    /** What the token would turn off (the confirmation page); nothing changes. */
    Optional<Subscription> check(String token);

    /** Turns the email off (idempotent). */
    Optional<Subscription> unsubscribe(String token);

    /** @param event a Settings › Notifications row, e.g. {@code payout}, {@code dispute} */
    record Subscription(String userId, String event, Locale locale) {}
}
