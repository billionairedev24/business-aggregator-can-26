package ca.northline.payments.api;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What a customer's money-moving request needs besides the PaymentIntent (S-55; the rules of the Finance workstream
 * and S-11, offered to the consumer side):
 *
 * <ul>
 *   <li>{@link #idempotent}: the {@code Idempotency-Key} contract (CLAUDE.md, stored 24 h): the first request with a
 *       key runs; a retry with the same key and body gets the stored answer; the same key with another body is 409
 *       {@code idempotency_key_reused}; a retry while the first runs is 409 {@code idempotency_in_progress}; a missing
 *       key is 422 "Idempotency-Key header is required.";
 *   <li>{@link #hasSecondFactor}: a phone-code-only session (no {@code acr=mfa}) confirms with a passkey or
 *       authenticator first — the {@code X-Step-Up} proof northline-auth issues (S-55 decision, docs/DECISIONS.md);
 *   <li>{@link #publishableKey}: for Stripe.js, when the live gateway is configured.
 * </ul>
 */
public interface ConsumerPayments {

    /** A stored or fresh answer: its HTTP status and JSON body; {@code replayed} when it came from the store. */
    record Answer(int status, String body, boolean replayed) {}

    /**
     * @param scope who and what, e.g. {@code customer:<userId>:booking-checkout}
     * @param request the request body (its fingerprint must match on retries)
     * @param status the HTTP status of a fresh answer
     */
    Answer idempotent(String scope, @Nullable String key, @Nullable Object request, int status, Supplier<?> action);

    /** True when the token carries {@code acr=mfa} or the step-up proof is valid, fresh, this user's and unused. */
    boolean hasSecondFactor(String userId, boolean tokenMfa, @Nullable String stepUpProof);

    /** Stripe's publishable key, or null with the fake gateway (payments are authorized without a card). */
    @Nullable
    String publishableKey();
}
