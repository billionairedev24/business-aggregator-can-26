package ca.northline.booking.domain;

import java.time.Duration;

/**
 * Minutes away for a service visit (mobile gaps part 2): the straight-line distance from the provider's latest position
 * to the job site, at the configured minutes per straight-line km — the leg rule of the courier routing (S-86
 * {@code RoutePlanner}, there 3 min/km for city couriers; here a car's), at least one minute. No road routing: no
 * routing provider is set up. Region-neutral: nothing here knows a place.
 */
public final class VisitEta {
    private VisitEta() {}

    private static final double EARTH_KM = 6371.0088;

    /** Great-circle distance in km (haversine). */
    public static double km(GeoPoint from, GeoPoint to) {
        var dLat = Math.toRadians(to.lat() - from.lat());
        var dLng = Math.toRadians(to.lng() - from.lng());
        var a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(from.lat()))
                        * Math.cos(Math.toRadians(to.lat()))
                        * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Whole minutes, rounded up, at least 1. */
    public static int minutes(double km, double minutesPerKm) {
        return (int) Math.max(1, Math.ceil(km * minutesPerKm));
    }

    /** Distance rounded to 0.1 km (what the customer sees; finer would place the provider). */
    public static double rounded(double km) {
        return Math.round(km * 10) / 10.0;
    }

    /** A position older than this is no longer live (Valkey keeps it 5 minutes anyway). */
    public static boolean stale(java.time.Instant at, java.time.Instant now, Duration ttl) {
        return at.plus(ttl).isBefore(now);
    }
}
