package ca.northline.console.adapters;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.console.map.*} (S-81): the delivery ops map's basemap.
 *
 * @param tiles an XYZ raster tile URL template with {@code {z}}, {@code {x}} and {@code {y}} ({@code CONSOLE_MAP_TILES},
 *     e.g. a self-hosted or commercial OpenStreetMap tile server); empty = no basemap, the zones on the console's own
 *     grid. Fetched by the staff member's browser: no key may be in it unless the provider restricts it by referrer
 * @param attribution the tile provider's required credit ({@code CONSOLE_MAP_ATTRIBUTION}), shown on the map
 */
@ConfigurationProperties("northline.console.map")
public record ConsoleMapProperties(
        @DefaultValue("") String tiles, @DefaultValue("") String attribution) {}
