package ca.northline.payments.web;

import ca.northline.payments.api.IdempotentRequests;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * {@code Idempotency-Key} for money-moving POSTs (CLAUDE.md): the first request with a key runs and its response is
 * kept 24 h; a retry with the same key and body gets the same response without running again; the same key with a
 * different body is refused (409 {@code idempotency_key_reused}); a retry while the first is still running gets 409
 * {@code idempotency_in_progress}. Keys are scoped to merchant + user + operation.
 */
@Component
@RequiredArgsConstructor
class PaymentsIdempotency {

    static final String HEADER = "Idempotency-Key";
    static final String REQUIRED = "Idempotency-Key header is required.";

    private final IdempotentRequests requests;

    ResponseEntity<String> run(
            String scope, @Nullable String key, @Nullable Object request, HttpStatus status, Supplier<?> action) {
        var outcome = requests.run(scope, key, request, status.value(), action);
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
