package ca.northline.auth.application;

import ca.northline.auth.domain.App;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-154): one-time codes sent by email ({@code auth.email_codes}) — confirming a new account's address
 * or resetting a password. Only hashes are stored.
 */
public interface EmailCodes {

    /** What a code is for. */
    enum Purpose {
        VERIFY_EMAIL("verify_email"),
        RESET_PASSWORD("reset_password");

        private final String code;

        Purpose(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Stores a new code; every earlier unused code of the same subject and purpose stops working. */
    void issue(StoredCode code);

    /** The newest code of the subject for this purpose (used or not). */
    Optional<StoredCode> latest(String subjectId, Purpose purpose);

    Optional<StoredCode> byTokenHash(String tokenHash);

    /** Counts a wrong guess; returns the new count. */
    int failed(String id);

    /** Uses the code up; false when it was already used (a concurrent request). */
    boolean use(String id, Instant at);

    /**
     * @param subjectId pending account id ({@link Purpose#VERIFY_EMAIL}) or user id ({@link Purpose#RESET_PASSWORD})
     */
    record StoredCode(
            String id,
            Purpose purpose,
            String subjectId,
            App app,
            String codeHash,
            String tokenHash,
            int failedAttempts,
            Instant createdAt,
            Instant expiresAt,
            @Nullable Instant usedAt) {}
}
