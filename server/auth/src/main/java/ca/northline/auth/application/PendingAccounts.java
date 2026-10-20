package ca.northline.auth.application;

import ca.northline.auth.domain.App;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-154): accounts created on the form whose email isn't confirmed yet ({@code auth.pending_accounts}).
 * One per address (case-insensitive); registering the same address again replaces it.
 */
public interface PendingAccounts {

    /** Inserts, or replaces the pending account with the same email (keeping that one's id). Returns the id kept. */
    String save(PendingAccount account);

    Optional<PendingAccount> findById(String id);

    Optional<PendingAccount> findByEmail(String email);

    void delete(String id);

    /** Forgets pending accounts and email codes that expired before {@code at}; returns how many accounts. */
    int purgeExpired(Instant at);

    /**
     * @param id the identity.users id the account gets once confirmed
     * @param phone optional, E.164, unverified
     * @param locale {@code en-CA} | {@code fr-CA}
     */
    record PendingAccount(
            String id,
            String email,
            String firstName,
            String lastName,
            @Nullable String phone,
            String passwordHash,
            String locale,
            App app,
            String termsVersion,
            Instant termsAcceptedAt,
            @Nullable String termsLanguage,
            @Nullable Instant termsEnglishRequestedAt,
            Instant expiresAt) {

        public boolean expired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
