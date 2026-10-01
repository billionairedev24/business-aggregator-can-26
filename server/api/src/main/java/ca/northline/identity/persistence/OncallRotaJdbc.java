package ca.northline.identity.persistence;

import ca.northline.identity.api.OncallRota;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.NotFound;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link OncallRota} over {@code identity.oncall_shifts} (V234). */
@Repository
@RequiredArgsConstructor
class OncallRotaJdbc implements OncallRota {

    private static final String SHIFT = """
            select s.id, s.user_id, u.display_name, s.starts_at, s.ends_at, s.duty
              from identity.oncall_shifts s join identity.users u on u.id = s.user_id""";

    private final JdbcClient jdbc;

    @Override
    public List<Shift> between(Instant from, Instant to) {
        return jdbc.sql(SHIFT + " where s.ends_at > :from and s.starts_at < :to order by s.starts_at, s.id")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> shift(rs))
                .list();
    }

    @Override
    public Optional<Shift> shift(String id) {
        return jdbc.sql(SHIFT + " where s.id = :id")
                .param("id", id)
                .query((rs, _) -> shift(rs))
                .optional();
    }

    @Override
    public Shift add(String userId, Instant startsAt, Instant endsAt, String duty, String createdBy) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into identity.oncall_shifts (id, user_id, starts_at, ends_at, duty, created_by)
                        values (:id, :u, :from, :to, :duty, :by)""")
                .param("id", id)
                .param("u", userId)
                .param("from", JdbcTimes.ts(startsAt))
                .param("to", JdbcTimes.ts(endsAt))
                .param("duty", duty)
                .param("by", createdBy)
                .update();
        return shift(id).orElseThrow();
    }

    @Override
    public Shift reassign(String id, String userId) {
        if (jdbc.sql("update identity.oncall_shifts set user_id = :u where id = :id")
                        .param("u", userId)
                        .param("id", id)
                        .update()
                == 0) {
            throw new NotFound("shift", id);
        }
        return shift(id).orElseThrow();
    }

    @Override
    public void remove(String id) {
        jdbc.sql("delete from identity.oncall_shifts where id = :id")
                .param("id", id)
                .update();
    }

    private static Shift shift(ResultSet rs) throws SQLException {
        return new Shift(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("display_name"),
                JdbcTimes.requiredInstant(rs, "starts_at"),
                JdbcTimes.requiredInstant(rs, "ends_at"),
                rs.getString("duty"));
    }
}
