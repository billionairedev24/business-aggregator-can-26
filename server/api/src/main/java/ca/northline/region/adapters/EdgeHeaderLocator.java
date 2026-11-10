package ca.northline.region.adapters;

import ca.northline.region.application.VisitorLocator;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code VISITOR_LOCATION_DETECTOR=edge} (S-192): the visitor's place from the CDN's headers. Cloudflare's first
 * (developers.cloudflare.com › Rules › Transform › Managed transforms › "Add visitor location headers"; the country
 * header {@code cf-ipcountry} is on for any proxied record), else the generic {@code X-Geo-*} set any other edge or a
 * load balancer can be told to add. A header the client wrote itself only moves its own guess (nothing is trusted
 * with it: the answer is shown and ranks, never filters or charges). Values are cleaned by {@link VisitorParts}.
 */
class EdgeHeaderLocator implements VisitorLocator {

    /** One source's header names, in the order country, region code, region name, city, postal code, lat, lng. */
    private record Names(
            String country, String regionCode, String region, String city, String postal, String lat, String lng) {}

    private static final Names CLOUDFLARE = new Names(
            "cf-ipcountry",
            "cf-region-code",
            "cf-region",
            "cf-ipcity",
            "cf-postal-code",
            "cf-iplatitude",
            "cf-iplongitude");
    private static final Names GENERIC = new Names(
            "X-Geo-Country",
            "X-Geo-Region-Code",
            "X-Geo-Region",
            "X-Geo-City",
            "X-Geo-Postal-Code",
            "X-Geo-Latitude",
            "X-Geo-Longitude");

    @Override
    public String name() {
        return "edge";
    }

    @Override
    public Optional<VisitorPlace> locate(VisitorRequest request) {
        return from(CLOUDFLARE, request).or(() -> from(GENERIC, request));
    }

    private Optional<VisitorPlace> from(Names n, VisitorRequest r) {
        var regionCode = VisitorParts.region(r.header().apply(n.regionCode()));
        var place = VisitorParts.place(
                r.header().apply(n.country()),
                regionCode != null ? regionCode : r.header().apply(n.region()),
                r.header().apply(n.city()),
                r.header().apply(n.postal()),
                number(r.header().apply(n.lat())),
                number(r.header().apply(n.lng())),
                name());
        return place.filter(p -> !p.empty());
    }

    private static @Nullable Double number(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
