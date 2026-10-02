package ca.northline.identity.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The account side of privacy requests (S-105): who the person is (to verify them and to find rows that kept their
 * contact), the "Deletion requested on …" mark the account screens show, and closing the account when its erasure
 * starts. Never in event payloads.
 */
public interface PrivacyAccounts {

    /** The account; empty for an unknown id. */
    Optional<Holder> holder(String userId);

    /** An account by its email (case-insensitive) or E.164 mobile number, for staff recording a request. */
    Optional<String> find(String emailOrPhone);

    /** Sets {@code identity.users.erasure_requested_at} ({@code null} clears it: the request was withdrawn). */
    void erasureRequested(String userId, @Nullable Instant at);

    /**
     * Closes the account for erasure, idempotently: status {@code erased} (northline-auth refuses it tokens), every
     * sign-in ended with reason {@code erased} (its auth session dies on the next request), passkeys and console roles
     * removed.
     */
    void close(String userId, Instant at);

    /**
     * @param status {@code active | suspended | erased}
     * @param locale {@code en-CA | fr-CA}
     */
    record Holder(
            String id,
            @Nullable String email,
            @Nullable String phone,
            String locale,
            String status,
            @Nullable Instant erasureRequestedAt) {

        public boolean erased() {
            return "erased".equals(status);
        }
    }
}
