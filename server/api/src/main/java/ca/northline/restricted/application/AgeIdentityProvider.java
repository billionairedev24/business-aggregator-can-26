package ca.northline.restricted.application;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the identity provider that checks a customer's age — a government photo ID plus a matching live selfie
 * (Stripe Identity {@code document} session). {@code northline.age-verification.provider}: {@code stripe} or
 * {@code local} (a fake whose outcome the developer picks on a local page; also the {@code test} profile's adapter).
 * Only the age in whole years leaves the adapter — never the date of birth, the name, the ID number or the images.
 */
public interface AgeIdentityProvider {

    /** Opens a session; the customer finishes it in the provider's hosted flow at {@link Session#url}. */
    Session start(StartRequest request);

    /** Reads a session; for a verified one, the person's age in whole years on {@code today} (computed in memory). */
    Result read(String sessionId, LocalDate today);

    /**
     * Asks the provider to delete the session's document, selfie and extracted data now that the result is kept
     * (Stripe: {@code POST /v1/identity/verification_sessions/{id}/redact}). Best effort.
     */
    void redact(String sessionId);

    /** The method code kept with a verified result. */
    String method();

    /**
     * @param reference our reference (the customer's id) — Stripe {@code client_reference_id}
     * @param attempt 1, 2, … — part of the idempotency key
     */
    record StartRequest(String reference, int attempt, String returnUrl) {}

    /** @param url the provider's single-use hosted-flow URL: never stored or logged */
    record Session(String id, String url) {}

    /**
     * @param state {@code requires_input | processing | verified | canceled}
     * @param age whole years on the day asked, for a verified session; else null
     */
    record Result(
            String state,
            @Nullable String lastError,
            @Nullable Integer age) {}
}
