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

    private PlaceParams() {}

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
