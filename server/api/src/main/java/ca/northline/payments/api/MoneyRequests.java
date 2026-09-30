package ca.northline.payments.api;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The payments module's guards for money-moving requests, for other modules' checkouts (S-57): the
 * {@code Idempotency-Key} store (24 h; Redis outside local/test) and the step-up proof check ({@code X-Step-Up}, the
 * same proofs payouts use). Additive facade over payments' own ports.
 */
public interface MoneyRequests {

    /** A stored key: {@code status == null} while the first request is still running. */
    record Stored(
            String fingerprint,
            @Nullable Integer status,
            @Nullable String body) {}

    /** Claims {@code (scope, key)}; empty when this call claimed it, else what was stored. */
    Optional<Stored> claim(String scope, String key, String fingerprint);

    void complete(String scope, String key, int status, String body);

    void release(String scope, String key);

    /**
     * Checks a step-up proof for {@code userId} and uses it up.
     *
     * @throws StepUpNeeded when it is missing, stale, reused or someone else's
     */
    void requireStepUp(String userId, @Nullable String proof);

    /** 403 {@code step_up_required} — the caller must confirm again (passkey or authenticator) and retry. */
    final class StepUpNeeded extends RuntimeException {
        public StepUpNeeded(String message) {
            super(message, null, false, false);
        }
    }
}
