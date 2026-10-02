package ca.northline.messaging.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One grant or withdrawal of one consent category by one person (S-108; {@code messaging.consent_records}): the proof
 * CASL asks the sender to keep. Only minimised evidence: a truncated IP address, hashes of the user agent and of the
 * address the consent covered.
 *
 * @param wordingVersion the consent wording shown ({@link ConsentWordings}); null for a withdrawal
 * @param language {@code en | fr}: the language the wording or page was shown in
 * @param actorId the staff member who recorded it ({@link ConsentSource#CONSOLE}), else null
 */
public record ConsentRecord(
        String id,
        String userId,
        ConsentCategory category,
        boolean granted,
        Instant at,
        ConsentSource source,
        @Nullable String wordingVersion,
        @Nullable String language,
        @Nullable String addressHash,
        @Nullable String ipPrefix,
        @Nullable String userAgentHash,
        @Nullable String actorId) {

    public ConsentRecord {
        if (granted != (wordingVersion != null)) {
            throw new IllegalArgumentException("A grant names its wording; a withdrawal doesn't");
        }
    }
}
