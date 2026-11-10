package ca.northline.region.adapters;

import ca.northline.region.application.VisitorLocator;
import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@code VISITOR_LOCATION_DETECTOR=geoip} (S-192): the client's address looked up in a MaxMind GeoLite2 City or
 * GeoIP2 City database ({@code northline.visitor-location.database}, an {@code .mmdb} file mounted into the pod; never
 * in the repository or the image — MaxMind's licence and its weekly updates, docs/runbooks/location.md). Read with
 * MaxMind's own reader ({@code com.maxmind.db:maxmind-db}, Apache-2.0, no dependencies), memory-mapped, with its node
 * cache. A private or unparseable address, or one the database doesn't know, is no guess.
 */
@Slf4j
class GeoIpDatabaseLocator implements VisitorLocator, AutoCloseable {

    /** One lookup: the database's record for an address (the City schema as maps and lists), or null. */
    @FunctionalInterface
    interface Lookup {
        @Nullable
        Map<String, Object> find(InetAddress address) throws IOException;
    }

    private final Lookup lookup;
    private final @Nullable Reader reader;

    GeoIpDatabaseLocator(Lookup lookup) {
        this.lookup = lookup;
        this.reader = null;
    }

    private GeoIpDatabaseLocator(Reader reader) {
        this.reader = reader;
        this.lookup = address -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> found = reader.get(address, Map.class);
            return found;
        };
    }

    /** Opens the database file; fails start-up with what to do when it is missing or unreadable. */
    static GeoIpDatabaseLocator open(String path) {
        var file = new File(path);
        if (path.isBlank() || !file.canRead()) {
            throw new IllegalStateException("VISITOR_LOCATION_DETECTOR=geoip needs a readable GeoLite2/GeoIP2 City"
                    + " database at VISITOR_LOCATION_DATABASE (now '" + path + "'): docs/runbooks/location.md");
        }
        try {
            var reader = new Reader(file, new CHMCache());
            log.info(
                    "Visitor location: {} ({})",
                    file.getName(),
                    reader.getMetadata().databaseType());
            return new GeoIpDatabaseLocator(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Can't read the GeoIP database " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "geoip";
    }

    @Override
    public Optional<VisitorPlace> locate(VisitorRequest request) {
        var address = request.clientAddress();
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        InetAddress ip;
        try {
            ip = InetAddress.ofLiteral(address.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress()) {
            return Optional.empty();
        }
        Map<String, Object> found;
        try {
            found = lookup.find(ip);
        } catch (IOException | RuntimeException e) {
            log.warn("GeoIP lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
        if (found == null) {
            return Optional.empty();
        }
        var subdivision = first(found.get("subdivisions"));
        var location = map(found.get("location"));
        return VisitorParts.place(
                        text(map(found.get("country")).get("iso_code")),
                        Optional.ofNullable(text(subdivision.get("iso_code"))).orElse(name(subdivision)),
                        name(map(found.get("city"))),
                        text(map(found.get("postal")).get("code")),
                        number(location.get("latitude")),
                        number(location.get("longitude")),
                        name())
                .filter(p -> !p.empty());
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }

    /** The English name of a record ({@code names.en}); the database's names are the place's, not the reader's. */
    private static @Nullable String name(Map<String, Object> record) {
        return text(map(record.get("names")).get("en"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> first(@Nullable Object o) {
        return o instanceof List<?> l && !l.isEmpty() ? map(l.getFirst()) : Map.of();
    }

    private static @Nullable String text(@Nullable Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable Double number(@Nullable Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
