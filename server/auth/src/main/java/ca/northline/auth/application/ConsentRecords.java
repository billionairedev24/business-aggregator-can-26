package ca.northline.auth.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-157): the api's CASL proof table {@code messaging.consent_records} (V300), which northline-auth
 * appends to when an account is created with a sign-up consent — the same shared database, as auth already writes
 * {@code identity.users}. Append-only; the api reads the newest row per category as the current state.
 */
public interface ConsentRecords {

    void append(Grant grant);

    /** One grant, minimised (address and user agent hashed, IP truncated). */
    record Grant(
            String id,
            String userId,
            String category,
            Instant at,
            String source,
            String wordingVersion,
            String language,
            @Nullable String addressHash,
            @Nullable String ipPrefix,
            @Nullable String userAgentHash) {}
}
