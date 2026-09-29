package ca.northline.shared;

import org.jspecify.annotations.Nullable;
import org.mapstruct.TargetType;

/**
 * MapStruct helpers for {@link CodedEnum} ↔ {@code text} columns. Persistence rows keep enum columns as
 * {@code String}; add {@code uses = CodedEnums.class} to the row↔domain mapper and MapStruct converts both ways.
 */
public final class CodedEnums {
    private CodedEnums() {}

    public static @Nullable String toCode(@Nullable CodedEnum value) {
        return value == null ? null : value.code();
    }

    public static <E extends Enum<E> & CodedEnum> @Nullable E fromCode(
            @Nullable String code, @TargetType Class<E> type) {
        return code == null ? null : CodedEnum.fromCode(type, code);
    }
}
