package ca.northline.merchants.domain;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the city shown next to the business name ("Prairie Wrench · {city}") out of the free-text addresses of the
 * Business step: the first of the region model's market cities an address names (S-134). Anything else leaves the
 * city empty.
 */
final class Cities {

    private Cities() {}

    static Optional<String> find(Collection<String> known, Stream<String> texts) {
        return texts.flatMap(text -> known.stream()
                        .filter(city -> Pattern.compile(
                                        "\\b" + Pattern.quote(city.toLowerCase(Locale.ROOT)) + "\\b",
                                        Pattern.UNICODE_CASE)
                                .matcher(text.toLowerCase(Locale.ROOT))
                                .find()))
                .findFirst();
    }
}
