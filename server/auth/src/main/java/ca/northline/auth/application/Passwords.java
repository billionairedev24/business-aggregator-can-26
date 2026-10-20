package ca.northline.auth.application;

import java.time.Instant;
import java.util.Optional;

/** Outbound port (S-154): the accounts' password hashes ({@code auth.passwords}). Never the password itself. */
public interface Passwords {

    Optional<String> hashOf(String userId);

    /** Sets (or replaces) the account's password hash. */
    void save(String userId, String hash, Instant at);
}
