package ca.northline.identity.persistence;

import ca.northline.identity.api.SignupDates;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link SignupDates} over {@code identity.users.created_at}. */
@Repository
@RequiredArgsConstructor
class SignupDatesJdbc implements SignupDates {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Instant> signedUpSince(Collection<String> userIds, Instant since) {
        var out = new HashMap<String, Instant>();
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select id, created_at from identity.users where id = any(:ids) and created_at >= :since")
                .param("ids", userIds.toArray(String[]::new))
                .param("since", JdbcTimes.ts(since))
                .query(rs -> {
                    out.put(Objects.requireNonNull(rs.getString(1)), JdbcTimes.requiredInstant(rs, "created_at"));
                });
        return out;
    }
}
