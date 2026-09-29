package ca.northline.merchants.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * jsonb columns ({@code legal_details}, {@code profile}, {@code settings}, {@code *_i18n}) are read as text and written
 * with {@code cast(:x as jsonb)} through {@code JdbcClient}; Spring Data JDBC has no jsonb mapping without custom
 * converters.
 */
@Component
class JsonColumns {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper json = JsonMapper.builder().build();

    Map<String, Object> map(@Nullable String text) {
        return text == null || text.isBlank() ? new LinkedHashMap<>() : json.readValue(text, MAP);
    }

    <T> T read(@Nullable String text, Class<T> type, T fallback) {
        return text == null || text.isBlank() || text.equals("{}") ? fallback : json.readValue(text, type);
    }

    String write(Object value) {
        return json.writeValueAsString(value);
    }

    /** {@code {"en": text}} for the {@code *_i18n} columns (the builder edits one language, see DECISIONS.md). */
    @Nullable
    String i18n(@Nullable String text) {
        return text == null ? null : write(Map.of("en", text));
    }

    @Nullable
    String fromI18n(@Nullable String column) {
        if (column == null) {
            return null;
        }
        var values = map(column);
        var en = values.get("en");
        return en != null
                ? String.valueOf(en)
                : values.values().stream().findFirst().map(String::valueOf).orElse(null);
    }
}
