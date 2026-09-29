package ca.northline.merchants.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the city shown next to the business name ("Prairie Wrench · Calgary") out of the free-text addresses of the
 * Business step. Only cities in launch and pilot provinces are recognised; anything else leaves the city empty.
 */
final class Cities {
    private static final List<String> KNOWN = List.of(
            "Calgary",
            "Edmonton",
            "Airdrie",
            "Cochrane",
            "Chestermere",
            "Okotoks",
            "Red Deer",
            "Lethbridge",
            "Medicine Hat",
            "Canmore",
            "St. Albert",
            "Sherwood Park",
            "Spruce Grove",
            "Grande Prairie",
            "Fort McMurray",
            "Vancouver",
            "Victoria",
            "Kelowna",
            "Surrey",
            "Burnaby",
            "Richmond",
            "Kamloops",
            "Toronto",
            "Ottawa",
            "Montréal",
            "Québec");

    private Cities() {}

    static Optional<String> find(Stream<String> texts) {
        return texts.flatMap(text -> KNOWN.stream()
                        .filter(city -> Pattern.compile(
                                        "\\b" + Pattern.quote(city.toLowerCase(Locale.ROOT)) + "\\b",
                                        Pattern.UNICODE_CASE)
                                .matcher(text.toLowerCase(Locale.ROOT))
                                .find()))
                .findFirst();
    }
}
