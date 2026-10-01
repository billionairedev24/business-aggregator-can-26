package ca.northline.availability.persistence;

import ca.northline.availability.api.ServiceAreas;
import ca.northline.availability.application.MarketZones;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ServiceAreas} over {@code availability.service_areas} and the zone geometry of V114; {@link MarketZones}: the
 * zones each market offers (V131).
 */
@Repository
@RequiredArgsConstructor
class ServiceAreaQueries implements ServiceAreas, MarketZones {

    private static final String POINT = "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography";

    private final JdbcClient jdbc;

    @Override
    public List<String> offered(@Nullable String marketId) {
        if (marketId == null) {
            return List.of();
        }
        return jdbc.sql("select name from availability.service_zones where market_id = :m order by sort, name")
                .param("m", marketId)
                .query((rs, _) -> rs.getString("name"))
                .list();
    }

    @Override
    public Set<String> defaults(@Nullable String marketId) {
        if (marketId == null) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(
                        "select name from availability.service_zones where market_id = :m and default_on order by sort")
                .param("m", marketId)
                .query((rs, _) -> rs.getString("name"))
                .list());
    }

    @Override
    public Map<String, List<String>> zones(Collection<String> merchantIds) {
        var result = new LinkedHashMap<String, List<String>>();
        if (merchantIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        select merchant_id, zone from availability.service_areas
                         where merchant_id in (:ids) order by merchant_id, zone
                        """)
                .param("ids", List.copyOf(merchantIds))
                .query((rs, _) -> Map.entry(rs.getString("merchant_id"), rs.getString("zone")))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), _ -> new ArrayList<>())
                        .add(e.getValue()));
        return result;
    }

    @Override
    public Set<String> covering(Collection<String> merchantIds, Place place) {
        var city = place.city();
        if (merchantIds.isEmpty() || (!place.hasPoint() && city == null)) {
            return Set.of();
        }
        var query = place.hasPoint()
                ? jdbc.sql("""
                                select distinct a.merchant_id from availability.service_areas a
                                  join availability.service_zones z on z.name = a.zone
                                 where a.merchant_id in (:ids) and ST_Covers(z.area, %s)
                                """.formatted(POINT)).param("lat", place.lat()).param("lng", place.lng())
                : jdbc.sql("""
                                select distinct a.merchant_id from availability.service_areas a
                                  join availability.service_zones z on z.name = a.zone
                                 where a.merchant_id in (:ids) and lower(z.city) = lower(:city)
                                """).param("city", city);
        return new HashSet<>(query.param("ids", List.copyOf(merchantIds))
                .query((rs, _) -> rs.getString(1))
                .list());
    }

    @Override
    public Optional<String> zoneAt(Place place) {
        if (!place.hasPoint()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select name from availability.service_zones where ST_Covers(area, %1$s)
                         order by ST_Distance(centre, %1$s), name limit 1
                        """.formatted(POINT))
                .param("lat", place.lat())
                .param("lng", place.lng())
                .query(String.class)
                .optional();
    }
}
