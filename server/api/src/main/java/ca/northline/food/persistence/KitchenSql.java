package ca.northline.food.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Column conversions shared by the food adapters ({@code text[]}, {@code jsonb}, {@code date}, nullable numbers). */
final class KitchenSql {
    private KitchenSql() {}

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final TypeReference<List<List<String>>> RANGES = new TypeReference<>() {};

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    static @Nullable List<String> stringsOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getArray(column) == null ? null : strings(rs, column);
    }

    static String[] array(Collection<String> values) {
        return values.toArray(String[]::new);
    }

    static @Nullable Integer intOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static @Nullable Long longOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static @Nullable LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    static <T> T read(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }

    static List<List<String>> ranges(@Nullable String json) {
        return json == null || json.isBlank() ? List.of() : JSON.readValue(json, RANGES);
    }

    /** {@code {"en": name}} for the V006 {@code name_i18n} columns the search projection reads. */
    static String i18n(String text) {
        return json(Map.of("en", text));
    }
}
