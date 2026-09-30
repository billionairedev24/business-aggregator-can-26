package ca.northline.payments.api;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code Idempotency-Key} for money-moving POSTs outside payments (S-51 checkout), the same store and rules as the
 * Finance endpoints: missing key → 422 "Idempotency-Key header is required."; same key + same body → the stored answer
 * ({@link Outcome#replayed}); same key + another body → 409 {@code idempotency_key_reused}; still running → 409
 * {@code idempotency_in_progress}. Kept 24 h; a failure releases the key so the client can retry with it.
 */
public interface IdempotentRequests {

    /**
     * @param scope who and what, e.g. {@code consumer:<userId>:checkout}
     * @param request the body, fingerprinted
     * @param status the HTTP status of a successful answer
     */
    Outcome run(String scope, @Nullable String key, @Nullable Object request, int status, Supplier<?> action);

    /** The JSON answer to send ({@code body}), and whether it is a replay. */
    record Outcome(int status, String body, boolean replayed) {}
}
