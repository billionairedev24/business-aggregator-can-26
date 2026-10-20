package ca.northline.catalogue.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.catalogue.api.InstantBook.State;
import ca.northline.catalogue.application.InstantBookStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link InstantBookStore} over {@code catalogue.instant_book_settings}. */
@Repository
@RequiredArgsConstructor
class InstantBookJdbc implements InstantBookStore {

    private static final String SELECT = """
            select enabled, paused_at, paused_cause, paused_reason, updated_at
              from catalogue.instant_book_settings where merchant_id = :id""";

    private final JdbcClient jdbc;

    @Override
    public Optional<State> lock(String merchantId) {
        return jdbc.sql(SELECT + " for update")
                .param("id", merchantId)
                .query(InstantBookJdbc::state)
                .optional();
    }

    @Override
    public Optional<State> find(String merchantId) {
        return jdbc.sql(SELECT)
                .param("id", merchantId)
                .query(InstantBookJdbc::state)
                .optional();
    }

    @Override
    public void save(
            String merchantId,
            boolean enabled,
            @Nullable Instant pausedAt,
            @Nullable String cause,
            @Nullable String reason,
            String actorId,
            Instant at) {
        jdbc.sql("""
                        insert into catalogue.instant_book_settings
                          (merchant_id, enabled, paused_at, paused_cause, paused_reason, updated_by, updated_at)
                        values (:id, :enabled, :pausedAt, :cause, :reason, :by, :at)
                        on conflict (merchant_id) do update
                           set enabled = excluded.enabled, paused_at = excluded.paused_at,
                               paused_cause = excluded.paused_cause, paused_reason = excluded.paused_reason,
                               updated_by = excluded.updated_by, updated_at = excluded.updated_at
                        """)
                .param("id", merchantId)
                .param("enabled", enabled)
                .param("pausedAt", ts(pausedAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("cause", cause, java.sql.Types.VARCHAR)
                .param("reason", reason, java.sql.Types.VARCHAR)
                .param("by", actorId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<String> pausedFor(String cause) {
        return jdbc.sql("""
                        select merchant_id from catalogue.instant_book_settings
                         where paused_cause = :cause order by merchant_id
                        """).param("cause", cause).query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1))).list();
    }

    private static State state(ResultSet rs, int i) throws SQLException {
        return new State(
                rs.getBoolean("enabled"),
                instant(rs, "paused_at"),
                rs.getString("paused_cause"),
                rs.getString("paused_reason"),
                instant(rs, "updated_at"));
    }
}
