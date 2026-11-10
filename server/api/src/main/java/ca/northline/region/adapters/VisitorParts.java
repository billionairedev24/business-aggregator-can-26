package ca.northline.region.adapters;

import ca.northline.region.application.VisitorLocator.VisitorPlace;
import ca.northline.shared.PlaceParams;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The detectors' one cleaning step (S-192): what a header or a database says is a guess from outside, so each part is
 * kept only when it is well formed — the country a two-letter code (not "XX" unknown or "T1" Tor), the city a place
 * name of at most 60 characters (validation-rules.md § Location), the postal code the permissive postal rule, the
 * point inside the map and both or neither. Percent-encoded values (some CDNs encode non-ASCII names) are decoded.
 */
final class VisitorParts {

    private static final Pattern REGION = Pattern.compile("[\\p{L}\\p{Nd}][\\p{L}\\p{M}\\p{Nd} .'’-]{0,59}");

    private VisitorParts() {}

    static Optional<VisitorPlace> place(
            @Nullable String country,
            @Nullable String region,
            @Nullable String city,
            @Nullable String postal,
            @Nullable Double lat,
            @Nullable Double lng,
            String source) {
        var c = clean(country);
        var code = c == null ? null : c.toUpperCase(Locale.ROOT);
        var point = lat != null
                && lng != null
                && !lat.isNaN()
                && !lng.isNaN()
                && Math.abs(lat) <= 90
                && Math.abs(lng) <= 180
                && !(lat == 0 && lng == 0);
        return Optional.of(new VisitorPlace(
                PlaceParams.isCountry(code) ? code : null,
                region(region),
                PlaceParams.cityOrNull(clean(city)),
                PlaceParams.postalOrNull(clean(postal)),
                point ? lat : null,
                point ? lng : null,
                source));
    }

    /** A subdivision's code or name, or null. */
    static @Nullable String region(@Nullable String raw) {
        var r = clean(raw);
        return r != null && REGION.matcher(r).matches() ? r : null;
    }

    /** Decoded when percent-encoded, control characters out, trimmed; null when nothing is left. */
    static @Nullable String clean(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        var s = raw;
        if (s.indexOf('%') >= 0) {
            try {
                s = URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        s = s.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        return s.isEmpty() ? null : s;
    }
}
