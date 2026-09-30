package ca.northline.auth.application;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port (S-29): every refresh token issued to a public client, by hash, for as long as its authorization (the
 * rotation family) exists ({@code auth.issued_refresh_tokens}). Spring Authorization Server forgets a refresh token
 * when it rotates it; this remembers that it once belonged to the family, so presenting it again is recognised.
 */
public interface IssuedRefreshTokens {

    /** Remembers a refresh token of this authorization (idempotent). */
    void remember(String tokenHash, String authorizationId, Instant issuedAt, Instant expiresAt);

    /** The authorization (family) a refresh token was issued to, while that authorization exists. */
    Optional<String> familyOf(String tokenHash);
}
