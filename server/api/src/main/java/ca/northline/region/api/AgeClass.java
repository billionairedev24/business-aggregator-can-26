package ca.northline.region.api;

import ca.northline.shared.CodedEnum;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The age-restriction class a taxonomy category carries ({@code catalogue.categories.age_class}, V341) and products and
 * dishes inherit. The minimum age per class is per province, region data ({@link AgeRules}); a seller can't lower it.
 */
public enum AgeClass implements CodedEnum {
    ALCOHOL,
    /** Tobacco and vaping products. */
    TOBACCO,
    /** Cannabis accessories (the catalogue sells no cannabis). */
    CANNABIS;

    public static Optional<AgeClass> of(@Nullable String code) {
        return code == null
                ? Optional.empty()
                : Arrays.stream(values()).filter(c -> c.code().equals(code)).findFirst();
    }
}
