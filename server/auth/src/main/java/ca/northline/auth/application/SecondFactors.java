package ca.northline.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: TOTP secrets and backup codes ({@code auth.totp_secrets}, {@code auth.backup_codes}). */
public interface SecondFactors {

    void saveTotp(String userId, String secret, Instant confirmedAt);

    Optional<StoredTotp> findTotp(String userId);

    /** Records the accepted step; false when a concurrent request already used it (replay). */
    boolean markTotpUsed(String userId, long step);

    /** Replaces every backup code of the user with these hashes. */
    void replaceBackupCodes(String userId, List<String> hashes, Instant at);

    /** Marks an unused code as used; false when it doesn't exist or was used. */
    boolean consumeBackupCode(String userId, String hash, Instant at);

    record StoredTotp(String secret, long lastUsedStep) {}
}
