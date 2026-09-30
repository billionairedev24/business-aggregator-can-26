package ca.northline.merchants.application;

import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.IdentitySessionState;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: Stripe Identity (S-22). One VerificationSession per owner — a government ID plus a matching live
 * selfie. {@code northline.identity.provider}: {@code stripe} (stripe-java) or {@code local} (a fake whose outcome the
 * developer picks on a local page; also the {@code test} profile's adapter).
 */
public interface IdentityVerification {

    /** Opens a document + selfie session for one owner; the owner finishes it in Stripe's hosted flow at {@code url}. */
    Session start(StartRequest request);

    /** Cancels a session that was replaced (best effort: an already finished session stays as it is). */
    void cancel(String sessionId);

    /**
     * Reads a session. For a verified session the adapter compares the verified name with {@code expected.legalName}
     * and the verified date of birth with the Stripe Connect person of the same name, in memory: only the results
     * leave the adapter — never the name, date of birth, ID number or images.
     */
    SessionResult read(String sessionId, Expected expected);

    /**
     * @param checkId our {@code owner_identity_checks} id (Stripe {@code client_reference_id})
     * @param attempt 1, 2, … — part of the idempotency key, so a retried request never opens two sessions
     * @param returnUrl where Stripe sends the owner when they finish
     * @param email prefills Stripe's flow ({@code provided_details.email}) for emailed links; null for the signed-in owner
     */
    record StartRequest(
            String merchantId,
            String checkId,
            String principalId,
            int attempt,
            String returnUrl,
            @Nullable String email) {}

    /** @param url Stripe's single-use hosted-flow URL — a bearer link: never stored, logged or kept longer than needed */
    record Session(String id, String url) {}

    /** @param stripeAccount the business's Connect account (its persons carry the dates of birth), null when none */
    record Expected(String legalName, @Nullable String stripeAccount) {}

    record SessionResult(
            IdentitySessionState state, @Nullable String lastError, IdentityMatch nameMatch, IdentityMatch dobMatch) {}
}
