package ca.northline.search.domain;

import ca.northline.shared.RuleViolation;

/** Where the person is (the location pill): WGS 84 degrees. */
public record Coordinates(double lat, double lng) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public Coordinates {
        if (lat < -90 || lat > 90) {
            throw RuleViolation.of("lat", "range", SearchMessages.LAT);
        }
        if (lng < -180 || lng > 180) {
            throw RuleViolation.of("lng", "range", SearchMessages.LNG);
        }
    }

    /** Great-circle distance in km (haversine). */
    public double kmTo(double otherLat, double otherLng) {
        var dLat = Math.toRadians(otherLat - lat);
        var dLng = Math.toRadians(otherLng - lng);
        var a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(otherLat)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
