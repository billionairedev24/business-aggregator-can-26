package ca.northline.region.domain;

import ca.northline.shared.RuleViolation;

/** WGS 84 coordinates in degrees. */
public record GeoPoint(double lat, double lng) {

    public static final String LAT_RANGE = "Latitude must be between -90 and 90.";
    public static final String LNG_RANGE = "Longitude must be between -180 and 180.";

    public GeoPoint {
        if (!(lat >= -90 && lat <= 90)) {
            throw RuleViolation.of("lat", "range", LAT_RANGE);
        }
        if (!(lng >= -180 && lng <= 180)) {
            throw RuleViolation.of("lng", "range", LNG_RANGE);
        }
    }

    /** Great-circle distance in km (haversine). */
    public double kmTo(GeoPoint other) {
        var dLat = Math.toRadians(other.lat - lat);
        var dLng = Math.toRadians(other.lng - lng);
        var h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}
