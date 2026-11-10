package ca.northline.region.adapters;

import ca.northline.region.application.VisitorLocator.VisitorPlace;
import ca.northline.shared.PlaceParams;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The detectors' one cleaning step (S-192): what a header or a database says is a guess from outside, so each part is
 * kept only when it is well formed — the country a two-letter code (not "XX" unknown or "T1" Tor), the city a place
 * name of at most 60 characters (validation-rules.md § Location), the postal code the permissive postal rule, the
 * point inside the map and both or neither. Percent-encoded values (some CDNs encode non-ASCII names) are decoded, and
 * UTF-8 read as ISO-8859-1 (how servlet containers read raw header bytes: "MontrÃ©al") is read again as UTF-8.
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

    /** A header's UTF-8 bytes read one byte a character, read again as UTF-8; anything else unchanged. */
    static String utf8(String s) {
        var high = false;
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c > 0xFF) {
                return s; // already decoded
            }
            high |= c >= 0x80;
        }
        if (!high) {
            return s;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1)))
                    .toString();
        } catch (CharacterCodingException e) {
            return s; // really ISO-8859-1 ("Montréal" sent as Latin-1)
        }
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
        s = utf8(s);
        s = s.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        return s.isEmpty() ? null : s;
    }
}
