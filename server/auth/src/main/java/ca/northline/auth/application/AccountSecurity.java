package ca.northline.auth.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what the signed-in person has set up (Studio Settings › Security) — passkeys in
 * {@code auth.user_credentials}, the authenticator in {@code auth.totp_secrets}, unused backup codes and the sign-in log
 * {@code identity.sessions}.
 */
public interface AccountSecurity {

    List<Passkey> passkeys(String userId);

    @Nullable
    Instant authenticatorSince(String userId);

    BackupCodes backupCodes(String userId);

    /** Newest first. */
    List<SignIn> recentSignIns(String userId, int limit);

    record Passkey(
            String id,
            String label,
            @Nullable Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    record BackupCodes(int remaining, @Nullable Instant issuedAt) {}

    record SignIn(
            String id,
            @Nullable String device,
            @Nullable String city,
            @Nullable String method,
            Instant at,
            @Nullable Instant lastSeenAt) {}
}
