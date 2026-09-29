package ca.northline.shared;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * {@code timestamptz} ↔ {@link Instant} for JdbcClient code without {@code java.sql.Timestamp} (banned by Checkstyle):
 * bind {@link #ts(Instant)} and read with {@link #instant(ResultSet, String)}.
 */
public final class JdbcTimes {
    private JdbcTimes() {}

    /** Bind value for a {@code timestamptz} parameter (null stays null). */
    public static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** For NOT NULL columns. */
    public static Instant requiredInstant(ResultSet rs, String column) throws SQLException {
        var value = instant(rs, column);
        if (value == null) {
            throw new SQLException("Column " + column + " is null");
        }
        return value;
    }
}
