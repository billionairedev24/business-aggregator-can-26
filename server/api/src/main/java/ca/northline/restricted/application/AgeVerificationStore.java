package ca.northline.restricted.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code restricted.age_verifications}. */
public interface AgeVerificationStore {

    Optional<Row> find(String userId);

    /** The row for an open session, locked for the update. */
    Optional<Row> lockBySession(String sessionId);

    void save(Row row);

    boolean delete(String userId);

    /**
     * @param state {@code pending | verified | failed}
     * @param sessionId only while pending
     */
    record Row(
            String userId,
            String state,
            @Nullable Integer overAge,
            @Nullable LocalDate verifiedOn,
            @Nullable String method,
            @Nullable String sessionId,
            @Nullable String lastError,
            int attempts,
            Instant startedAt,
            Instant updatedAt) {}
}
