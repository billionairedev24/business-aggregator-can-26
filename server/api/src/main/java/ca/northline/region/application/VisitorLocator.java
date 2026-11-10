package ca.northline.region.application;

import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-192): where a visitor probably is, from the request alone — never the browser's position, never a
 * prompt. Amazon-style passive detection: the consumer header says "Deliver to {city}" before anyone chooses. The
 * answer only ranks (S-190: a location is never required); it is shown as a guess and is replaced by any choice.
 * Chosen by {@code northline.visitor-location.detector} ({@code VISITOR_LOCATION_DETECTOR}):
 *
 * <ul>
 *   <li>{@code edge}: the CDN's visitor location headers — Cloudflare's {@code cf-ipcountry}, {@code cf-ipcity},
 *       {@code cf-region-code} / {@code cf-region}, {@code cf-postal-code}, {@code cf-iplatitude},
 *       {@code cf-iplongitude} (sent when the zone's "Add visitor location headers" managed transform is on and the
 *       record is proxied), else the generic {@code X-Geo-Country}, {@code X-Geo-Region}, {@code X-Geo-City},
 *       {@code X-Geo-Postal-Code}, {@code X-Geo-Latitude}, {@code X-Geo-Longitude} any other edge can set;
 *   <li>{@code geoip}: a MaxMind GeoLite2 / GeoIP2 City database file ({@code northline.visitor-location.database},
 *       never in the repository — its licence; docs/runbooks/location.md) looked up by the client's address;
 *   <li>{@code none} (default): nothing is detected; the header says "Choose location".
 * </ul>
 */
public interface VisitorLocator {

    /** The detector's name ({@code edge}, {@code geoip}, {@code none}), echoed as {@link VisitorPlace#source()}. */
    String name();

    Optional<VisitorPlace> locate(VisitorRequest request);

    /**
     * What a detector may read of the request: a header by name (case-insensitive) and the client's address (the
     * right-most public hop of {@code X-Forwarded-For}, {@code ca.northline.shared.ClientAddress}).
     */
    record VisitorRequest(
            Function<String, @Nullable String> header,
            @Nullable String clientAddress) {}

    /**
     * A guess at the visitor's place; every part may be missing (Cloudflare without the managed transform sends the
     * country only).
     *
     * @param country ISO 3166-1 alpha-2
     * @param region the subdivision's code where known ("AB", "CA", "IDF"), else its name
     * @param city the locality's name
     * @param postalCode as the source wrote it (often only its first part: "T2P", "SW1A")
     */
    record VisitorPlace(
            @Nullable String country,
            @Nullable String region,
            @Nullable String city,
            @Nullable String postalCode,
            @Nullable Double lat,
            @Nullable Double lng,
            String source) {

        public boolean empty() {
            return country == null && city == null && postalCode == null && lat == null;
        }
    }
}
