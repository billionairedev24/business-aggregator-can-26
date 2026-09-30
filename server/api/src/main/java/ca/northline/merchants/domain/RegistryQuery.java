package ca.northline.merchants.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One lookup: which source, what kind of record, the number the owner entered and the names the record may carry
 * (legal name, operating / trade name, display name — any one of them matching is enough).
 *
 * @param registry the regulator of a licence ({@code AMVIC}, {@code Mobile permit}); null for business records
 */
public record RegistryQuery(
        RegistrySource source,
        RegistrySubject subject,
        @Nullable String registry,
        String number,
        List<String> expectedNames) {

    public RegistryQuery {
        number = number.strip();
        expectedNames = expectedNames.stream()
                .filter(n -> !n.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    public @Nullable String expectedName() {
        return expectedNames.isEmpty() ? null : expectedNames.getFirst();
    }
}
