package ca.northline.region.api;

import java.util.Optional;

/**
 * The market shown when nothing is known about a visitor — the same one {@code GET /api/v1/geo/markets} answers as
 * {@code fallback} (S-47: the first live market of the default market's province). Region configuration, never a city
 * in code; empty when none is configured.
 */
public interface FallbackMarket {

    /** @param province two-letter code */
    record City(String city, String province) {}

    Optional<City> fallback();
}
