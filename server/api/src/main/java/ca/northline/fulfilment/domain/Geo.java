package ca.northline.fulfilment.domain;

/** Straight-line distances on the earth (haversine). */
public final class Geo {
    private Geo() {}

    private static final double EARTH_KM = 6371.0088;

    public static double km(double lat1, double lng1, double lat2, double lng2) {
        var dLat = Math.toRadians(lat2 - lat1);
        var dLng = Math.toRadians(lng2 - lng1);
        var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2)
                        * Math.sin(dLng / 2);
        return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
