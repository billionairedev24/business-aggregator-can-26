package ca.northline.account.persistence;

import ca.northline.account.application.Preferences.PreferencesStore;
import ca.northline.account.application.Preferences.Stored;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PreferencesStore} over {@code account.preferences} (V162). */
@Repository
@RequiredArgsConstructor
class PreferencesJdbc implements PreferencesStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Stored> find(String userId) {
        return jdbc.sql("""
                        select province, units, time_format, dietary, allergies, accessibility, access_notes, display
                          from account.preferences where user_id = :u
                        """)
                .param("u", userId)
                .query((rs, _) -> new Stored(
                        rs.getString("province"),
                        rs.getString("units"),
                        rs.getString("time_format"),
                        strings(rs.getArray("dietary")),
                        rs.getString("allergies"),
                        strings(rs.getArray("accessibility")),
                        rs.getString("access_notes"),
                        strings(rs.getArray("display"))))
                .optional();
    }

    @Override
    public void save(String userId, Stored s) {
        jdbc.sql("""
                        insert into account.preferences (user_id, province, units, time_format, dietary, allergies,
                               accessibility, access_notes, display, updated_at)
                        values (:u, :province, :units, :time, :dietary, :allergies, :access, :notes, :display, now())
                        on conflict (user_id) do update set province = excluded.province, units = excluded.units,
                               time_format = excluded.time_format, dietary = excluded.dietary,
                               allergies = excluded.allergies, accessibility = excluded.accessibility,
                               access_notes = excluded.access_notes, display = excluded.display,
                               updated_at = excluded.updated_at
                        """)
                .param("u", userId)
                .param("province", s.province())
                .param("units", s.units())
                .param("time", s.timeFormat())
                .param("dietary", s.dietary().toArray(String[]::new))
                .param("allergies", s.allergies())
                .param("access", s.accessibility().toArray(String[]::new))
                .param("notes", s.accessNotes())
                .param("display", s.display().toArray(String[]::new))
                .update();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
