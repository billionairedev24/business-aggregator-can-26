package ca.northline.payments.infra;

import ca.northline.payments.api.ConsumerPayments;
import ca.northline.payments.application.IdempotencyStore;
import ca.northline.payments.application.StepUpRequired;
import ca.northline.payments.application.StepUpVerifier;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ConsumerPayments} over the payments module's idempotency store (Redis in the cloud, the database under
 * local/test) and step-up verifier — the same behaviour as the Studio's money-moving endpoints ({@code
 * PaymentsIdempotency}), for the consumer side.
 */
@Component
@RequiredArgsConstructor
class ConsumerPaymentsAdapter implements ConsumerPayments {

    static final String HEADER = "Idempotency-Key";
    static final String REQUIRED = "Idempotency-Key header is required.";

    private final IdempotencyStore store;
    private final StepUpVerifier stepUp;
    private final PaymentsProperties properties;
    private final JsonMapper json;

    @Override
    public Answer idempotent(
            String scope, @Nullable String key, @Nullable Object request, int status, Supplier<?> action) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw RuleViolation.of(HEADER, "required", REQUIRED);
        }
        var fingerprint = sha256(request == null ? "" : json.writeValueAsString(request));
        var existing = store.claim(scope, key, fingerprint, IdempotencyStore.TTL);
        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new Conflict(
                        "idempotency_key_reused", "This Idempotency-Key was already used for a different request.");
            }
            if (stored.status() == null || stored.body() == null) {
                throw new Conflict("idempotency_in_progress", "The first request with this key is still running.");
            }
            return new Answer(stored.status(), stored.body(), true);
        }
        try {
            var body = json.writeValueAsString(action.get());
            store.complete(scope, key, status, body);
            return new Answer(status, body, false);
        } catch (RuntimeException e) {
            store.release(scope, key);
            throw e;
        }
    }

    @Override
    public boolean hasSecondFactor(String userId, boolean tokenMfa, @Nullable String stepUpProof) {
        if (tokenMfa) {
            return true;
        }
        if (stepUpProof == null || stepUpProof.isBlank()) {
            return false;
        }
        try {
            stepUp.verify(userId, stepUpProof);
            return true;
        } catch (StepUpRequired e) {
            return false;
        }
    }

    @Override
    public @Nullable String publishableKey() {
        var secret = properties.stripeSecretKey();
        var key = properties.stripePublishableKey();
        return secret == null || secret.isBlank() || key == null || key.isBlank() ? null : key;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
