package ca.northline.console.adapters;

import ca.northline.console.application.ViewDeliveryMap.Basemap;
import ca.northline.console.application.ViewDeliveryMap.Basemaps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The delivery ops map's basemap from {@code northline.console.map.*} ({@code CONSOLE_MAP_TILES}; S-81). */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleMapProperties.class)
class BasemapConfiguration {

    @Bean
    Basemaps basemaps(ConsoleMapProperties props) {
        var tiles = props.tiles().strip();
        if (tiles.isEmpty()) {
            log.info("Console map: no basemap (CONSOLE_MAP_TILES) — delivery zones are drawn on the console's grid.");
            return () -> null;
        }
        if (!tiles.startsWith("https://")
                || !tiles.contains("{z}")
                || !tiles.contains("{x}")
                || !tiles.contains("{y}")) {
            throw new IllegalStateException(
                    "CONSOLE_MAP_TILES is an https:// XYZ tile URL template with {z}, {x} and {y}, not: " + tiles);
        }
        var basemap = new Basemap(tiles, props.attribution().strip());
        log.info("Console map: XYZ basemap tiles from {}.", tiles.replaceAll("\\?.*", ""));
        return () -> basemap;
    }
}
