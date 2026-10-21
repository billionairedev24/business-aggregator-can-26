package ca.northline.identity.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.identity.api.CustomerNoShows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link CustomerNoShows} on {@code identity.users.no_show_count / no_show_restricted_at} (V445). */
@Repository
@RequiredArgsConstructor
@Transactional
class CustomerNoShowJdbc implements CustomerNoShows {

    private final JdbcClient jdbc;

    @Override
    public void restrict(String userId, int confirmedNoShows, Instant at) {
        jdbc.sql("""
                        update identity.users
                           set no_show_count = :count, no_show_restricted_at = coalesce(no_show_restricted_at, :at)
                         where id = :id
                        """)
                .param("count", confirmedNoShows)
                .param("at", ts(at))
                .param("id", userId)
                .update();
    }

    @Override
    public boolean lift(String userId) {
        return jdbc.sql("""
                        update identity.users set no_show_restricted_at = null, no_show_count = 0
                         where id = :id and no_show_restricted_at is not null
                        """).param("id", userId).update() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Restriction> of(String userId) {
        return jdbc.sql("""
                        select no_show_count, no_show_restricted_at from identity.users
                         where id = :id and no_show_restricted_at is not null
                        """)
                .param("id", userId)
                .query((rs, _) ->
                        new Restriction(rs.getInt("no_show_count"), requiredInstant(rs, "no_show_restricted_at")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> restricted() {
        return jdbc.sql("select id from identity.users where no_show_restricted_at is not null order by id")
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }
}
