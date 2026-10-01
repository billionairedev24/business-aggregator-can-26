package ca.northline.region.domain;

import org.jspecify.annotations.Nullable;

/**
 * An address as a places provider returns it, in Canada-shaped parts. {@code province} is the two-letter code,
 * {@code postalCode} as written by Canada Post ("T2T 0B7").
 */
public record PlaceParts(
        String placeId,
        String formatted,
        @Nullable String streetNumber,
        @Nullable String route,
        @Nullable String neighbourhood,
        @Nullable String city,
        @Nullable String province,
        @Nullable String postalCode,
        @Nullable String country,
        GeoPoint point) {

    /** "1204 17 Ave SW" — number and street, else the first part of the formatted address. */
    public String street() {
        if (route != null && !route.isBlank()) {
            return streetNumber == null || streetNumber.isBlank() ? route : streetNumber + " " + route;
        }
        var comma = formatted.indexOf(',');
        return comma > 0 ? formatted.substring(0, comma) : formatted;
    }

    public boolean inCanada() {
        return "CA".equalsIgnoreCase(country);
    }
}
