package ca.northline.catalogue.persistence;

import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Column conversions shared by the catalogue adapters ({@code timestamptz}, {@code text[]}, {@code jsonb}). */
final class Sql {
    private Sql() {}

    static final JsonMapper JSON = JsonMapper.builder().build();

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

    static String[] array(Collection<String> values) {
        return values.toArray(String[]::new);
    }

    static String[] codes(Collection<? extends CodedEnum> values) {
        return values.stream().map(CodedEnum::code).toArray(String[]::new);
    }

    static <E extends Enum<E> & CodedEnum> List<E> enums(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        return strings(rs, column).stream()
                .map(c -> CodedEnum.fromCode(type, c))
                .toList();
    }

    static <E extends Enum<E> & CodedEnum> @Nullable E enumOrNull(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        var code = rs.getString(column);
        return code == null ? null : CodedEnum.fromCode(type, code);
    }

    static @Nullable Long longOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static @Nullable Integer intOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    static Map<String, String> stringMap(@Nullable String json) {
        return json == null ? Map.of() : JSON.readValue(json, new TypeReference<Map<String, String>>() {});
    }

    static <T> List<T> list(@Nullable String json, Class<T> element) {
        return json == null
                ? List.of()
                : JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, element));
    }
}
