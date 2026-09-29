package ca.northline.shared;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/**
 * Enum stored/serialized by its lower-case code ({@code PROVIDER} ↔ {@code "provider"}), matching the
 * {@code text + CHECK} enums in the migrations and the camelCase/lower-case JSON of the API.
 *
 * <p>Implement it on every enum that maps a DB enum column; Spring Data JDBC and Jackson pick it up automatically.
 */
public interface CodedEnum {

    @JsonValue
    default String code() {
        return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        return Arrays.stream(type.getEnumConstants())
                .filter(e -> e.code().equals(code))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown %s code '%s'".formatted(type.getSimpleName(), code)));
    }
}
