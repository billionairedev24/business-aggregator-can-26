package ca.northline.shared;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The visitor's place on the consumer site's public reads (S-190 "Location is never required"): home, Shop, kitchens.
 * Every part is optional — a page with no place shows every live market, ranked by popularity — but a value that is
 * sent must be well formed, with the same 422 messages everywhere ({@code docs/spec/validation-rules.md} § Location).
 */
public final class PlaceParams {

    public static final int CITY_MAX = 60;
    public static final String CITY_TOO_LONG = "At most 60 characters.";
    public static final String CITY_FORMAT = "Enter a city name.";
    public static final String POINT_PAIR = "Send both lat and lng, or neither.";
    public static final String LAT_RANGE = "Latitude must be between -90 and 90.";
    public static final String LNG_RANGE = "Longitude must be between -180 and 180.";

    /** Letters (any script, with accents), digits, spaces and the punctuation of place names ("St. John's", "Rivière-du-Loup"). */
    private static final Pattern CITY = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}][\\p{L}\\p{M}\\p{Nd} .'’-]*");

    /*
     * S-192: a postal or ZIP code of any country ("T2P 1B5", "90210", "SW1A 1AA", "75008", "110-0001"): no country's
     * pattern here (S-192a brings per-country formats). Letters and digits, inner spaces and hyphens, at most 12.
     */
    public static final int POSTAL_MAX = 12;
    public static final String POSTAL_REQUIRED = "Enter a postal or ZIP code.";
    public static final String POSTAL_TOO_LONG = "At most 12 characters.";
    public static final String POSTAL_FORMAT = "Use letters, digits, spaces and hyphens only.";
    public static final String COUNTRY_FORMAT = "Choose a country from the list.";
    private static final Pattern POSTAL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9 -]*[A-Za-z0-9])?");
    /** ISO 3166-1 alpha-2 ("CA", "US", "FR"); the user-assigned XA–XZ and Cloudflare's "XX"/"T1" are not countries. */
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private PlaceParams() {}

    /**
     * A postal or ZIP code as typed (trimmed, inner runs of spaces made one, upper-cased): required, at most
     * {@value #POSTAL_MAX} characters, letters, digits, spaces and hyphens ({@code docs/spec/validation-rules.md}
     * § Location).
     */
    public static String postalCode(String field, @Nullable String value) {
        var code = value == null ? "" : value.strip().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            throw RuleViolation.of(field, "required", POSTAL_REQUIRED);
        }
        if (code.length() > POSTAL_MAX) {
            throw RuleViolation.of(field, "length", POSTAL_TOO_LONG);
        }
        if (!POSTAL.matcher(code).matches()) {
            throw RuleViolation.of(field, "format", POSTAL_FORMAT);
        }
        return code;
    }

    /** A country, ISO 3166-1 alpha-2 (upper-cased): blank or absent = not given (null). */
    public static @Nullable String country(String field, @Nullable String value) {
        var code = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            return null;
        }
        if (!isCountry(code)) {
            throw RuleViolation.of(field, "format", COUNTRY_FORMAT);
        }
        return code;
    }

    /** Whether {@code code} is a two-letter country code (not Cloudflare's "XX" unknown or "T1" Tor, not XA–XZ). */
    public static boolean isCountry(@Nullable String code) {
        return code != null && COUNTRY.matcher(code).matches() && !code.startsWith("X");
    }

    /** {@link #postalCode} without the exception: the normalised code, or null when it is not one. */
    public static @Nullable String postalOrNull(@Nullable String value) {
        try {
            return value == null || value.isBlank() ? null : postalCode("postalCode", value);
        } catch (RuleViolation e) {
            return null;
        }
    }

    /** {@link #city} without the exception: the name, or null when it is not one. */
    public static @Nullable String cityOrNull(@Nullable String value) {
        try {
            return city("city", value);
        } catch (RuleViolation e) {
            return null;
        }
    }

    /**
     * A city (or market) name: blank or absent = not known (null); too long or with characters no place name has is a
     * {@link RuleViolation} on {@code field}.
     */
    public static @Nullable String city(String field, @Nullable String value) {
        var name = value == null ? "" : value.strip();
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > CITY_MAX) {
            throw RuleViolation.of(field, "length", CITY_TOO_LONG);
        }
        if (!CITY.matcher(name).matches()) {
            throw RuleViolation.of(field, "format", CITY_FORMAT);
        }
        return name;
    }

    /** The visitor's point: both or neither, inside the map. */
    public static void point(@Nullable Double lat, @Nullable Double lng) {
        if (lat != null && (lat.isNaN() || lat < -90 || lat > 90)) {
            throw RuleViolation.of("lat", "range", LAT_RANGE);
        }
        if (lng != null && (lng.isNaN() || lng < -180 || lng > 180)) {
            throw RuleViolation.of("lng", "range", LNG_RANGE);
        }
        if ((lat == null) != (lng == null)) {
            throw RuleViolation.of(lat == null ? "lat" : "lng", "required", POINT_PAIR);
        }
    }

    /** Great-circle distance in km (haversine), the one the public reads rank by. */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        var dLat = Math.toRadians(lat2 - lat1);
        var dLng = Math.toRadians(lng2 - lng1);
        var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2)
                        * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
