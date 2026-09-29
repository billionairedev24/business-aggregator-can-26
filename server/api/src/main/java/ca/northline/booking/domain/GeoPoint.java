package ca.northline.booking.domain;

import ca.northline.shared.RuleViolation;

/** WGS84 position logged with a job transition (GPS check-in, dispute evidence). */
public record GeoPoint(double lat, double lng) {
    public GeoPoint {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw RuleViolation.of("lat", "range", "Location is not a valid position.");
        }
    }
}
