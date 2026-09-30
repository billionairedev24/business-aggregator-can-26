package ca.northline.payments.application;

import ca.northline.payments.api.IdempotentRequests;
import ca.northline.payments.api.PaymentStepUp;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Finance endpoints' Idempotency-Key and step-up rules as a service, so other modules' money-moving endpoints
 * (S-51 checkout) apply exactly the same ones ({@link IdempotentRequests}, {@link PaymentStepUp}).
 */
@Service
@RequiredArgsConstructor
class RequestGuards implements IdempotentRequests, PaymentStepUp {

    static final String HEADER = "Idempotency-Key";
    static final String REQUIRED = "Idempotency-Key header is required.";

    private final IdempotencyStore store;
    private final StepUpVerifier stepUp;
    private final JsonMapper json;

    @Override
    public Outcome run(String scope, @Nullable String key, @Nullable Object request, int status, Supplier<?> action) {
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
            var storedStatus = stored.status();
            var body = stored.body();
            if (storedStatus == null || body == null) {
                throw new Conflict("idempotency_in_progress", "The first request with this key is still running.");
            }
            return new Outcome(storedStatus, body, true);
        }
        try {
            var body = json.writeValueAsString(action.get());
            store.complete(scope, key, status, body);
            return new Outcome(status, body, false);
        } catch (RuntimeException e) {
            store.release(scope, key);
            throw e;
        }
    }

    @Override
    public boolean verified(String userId, @Nullable String proof) {
        try {
            stepUp.verify(userId, proof);
            return true;
        } catch (StepUpRequired e) {
            return false;
        }
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
