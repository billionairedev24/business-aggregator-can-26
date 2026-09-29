package ca.northline.messaging.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** Column conversions shared by the messaging adapters ({@code timestamptz}, {@code text[]}, {@code jsonb}). */
final class MessagingSql {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private MessagingSql() {}

    static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static Instant requiredInstant(ResultSet rs, String column) throws SQLException {
        var value = instant(rs, column);
        if (value == null) {
            throw new IllegalStateException(column + " is null");
        }
        return value;
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
