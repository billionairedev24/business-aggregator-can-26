package ca.northline.payments.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How long a manual-capture card authorization holds, and when Northline renews it (docs/DECISIONS.md, S-11).
 *
 * <p>Escrow is captured at fulfilment, so the authorization only has to last from the hold (quote accepted, order
 * placed) to the completed job / delivery / handoff. Online card authorizations last 7 days at Stripe
 * ({@code capture_before} on the charge says exactly when); a job booked further out would lose its hold. Policy:
 *
 * <ol>
 *   <li>Every authorization saves the card for off-session use ({@code setup_future_usage=off_session}).
 *   <li>{@link #LEAD} before it lapses, the re-authorization job places a <b>new</b> manual-capture PaymentIntent for
 *       the same amount off-session with the saved card, and only when that one is authorized cancels the old one
 *       and points the escrow at the new one (no gap, never two captures).
 *   <li>A re-authorization Stripe declines or that needs the customer (3-D Secure) keeps the old hold, publishes
 *       {@code payment.reauthorization_required} so the customer can confirm again, and is retried every
 *       {@link #RETRY_EVERY} until the old hold lapses.
 *   <li>Incremental authorization and extended (30-day) authorizations are not requested: Stripe offers them only on
 *       IC+ pricing and only for some card brands, and a quote that grows is a new quote version, i.e. a new hold.
 * </ol>
 */
public final class AuthorizationWindow {

    /** Stripe: online card payments must be captured within 7 days of the authorization. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    /** Renew this long before the hold lapses — several job runs and a retry before it does. */
    public static final Duration LEAD = Duration.ofHours(36);

    /** A failed renewal is tried again after this long. */
    public static final Duration RETRY_EVERY = Duration.ofHours(12);

    private AuthorizationWindow() {}

    /** When the hold lapses: Stripe's {@code capture_before}, else the authorization time + 7 days. */
    public static Instant lapsesAt(@Nullable Instant captureBefore, Instant authorizedAt) {
        return captureBefore != null ? captureBefore : authorizedAt.plus(VALIDITY);
    }

    /** When the renewal job should pick the hold up. */
    public static Instant renewFrom(@Nullable Instant captureBefore, Instant authorizedAt) {
        return lapsesAt(captureBefore, authorizedAt).minus(LEAD);
    }

    /** Whether a hold is due for renewal now (and a previous failed attempt isn't too recent). */
    public static boolean renewalDue(
            @Nullable Instant captureBefore, Instant authorizedAt, @Nullable Instant lastFailure, Instant now) {
        if (now.isBefore(renewFrom(captureBefore, authorizedAt))
                || !now.isBefore(lapsesAt(captureBefore, authorizedAt))) {
            return false;
        }
        return lastFailure == null || !now.isBefore(lastFailure.plus(RETRY_EVERY));
    }
}
