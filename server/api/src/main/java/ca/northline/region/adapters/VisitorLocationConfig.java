package ca.northline.region.adapters;

import ca.northline.region.application.VisitorLocator;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link VisitorLocator} for {@code northline.visitor-location.detector} ({@code VISITOR_LOCATION_DETECTOR}, S-192):
 * {@code none} (default), {@code edge} (the CDN's visitor location headers) or {@code geoip} (a GeoLite2 / GeoIP2 City
 * file at {@code northline.visitor-location.database}). docs/runbooks/location.md.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class VisitorLocationConfig {

    @Bean // the GeoIP reader is closed with the context (AutoCloseable)
    VisitorLocator visitorLocator(
            @Value("${northline.visitor-location.detector:none}") String detector,
            @Value("${northline.visitor-location.database:}") String database) {
        return switch (detector.strip().toLowerCase(Locale.ROOT)) {
            case "", "none" -> NONE;
            case "edge" -> {
                log.info("Visitor location: the edge's headers (cf-ip*, X-Geo-*)");
                yield new EdgeHeaderLocator();
            }
            case "geoip" -> GeoIpDatabaseLocator.open(database);
            default ->
                throw new IllegalStateException(
                        "VISITOR_LOCATION_DETECTOR must be none, edge or geoip, not " + detector);
        };
    }

    /** Nothing detected: the header offers "Choose location". */
    static final VisitorLocator NONE = new VisitorLocator() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public Optional<VisitorPlace> locate(VisitorRequest request) {
            return Optional.empty();
        }
    };
}
