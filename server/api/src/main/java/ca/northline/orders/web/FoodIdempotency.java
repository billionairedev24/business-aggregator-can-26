package ca.northline.orders.web;

import ca.northline.payments.api.MoneyRequests;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} on the food checkout's money-moving POSTs — the payments module's rules and store (S-11 /
 * Finance): required; same key + same body → the stored response ({@code Idempotent-Replayed: true}); same key + other
 * body → 409 {@code idempotency_key_reused}; still running → 409 {@code idempotency_in_progress}; a failure frees the key.
 */
@Component
@RequiredArgsConstructor
class FoodIdempotency {

    static final String HEADER = "Idempotency-Key";
    static final String REQUIRED = "Idempotency-Key header is required.";

    private final MoneyRequests store;
    private final JsonMapper json = JsonMapper.builder().build();

    ResponseEntity<String> run(
            String scope, @Nullable String key, @Nullable Object request, HttpStatus status, Supplier<?> action) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw RuleViolation.of(HEADER, "required", REQUIRED);
        }
        var fingerprint = sha256(request == null ? "" : json.writeValueAsString(request));
        var existing = store.claim(scope, key, fingerprint);
        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new Conflict(
                        "idempotency_key_reused", "This Idempotency-Key was already used for a different request.");
            }
            if (stored.status() == null || stored.body() == null) {
                throw new Conflict("idempotency_in_progress", "The first request with this key is still running.");
            }
            return ResponseEntity.status(stored.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotent-Replayed", "true")
                    .body(stored.body());
        }
        try {
            var body = json.writeValueAsString(action.get());
            store.complete(scope, key, status.value(), body);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (RuntimeException e) {
            store.release(scope, key);
            throw e;
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
