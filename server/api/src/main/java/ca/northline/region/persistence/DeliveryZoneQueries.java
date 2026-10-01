package ca.northline.region.persistence;

import ca.northline.region.api.DeliveryZones;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link DeliveryZones} over {@code region.zones}: the outer ring of each geography polygon, point by point. */
@Repository
@RequiredArgsConstructor
class DeliveryZoneQueries implements DeliveryZones {

    private final JdbcClient jdbc;

    @Override
    public List<Zone> inMarket(String marketId) {
        var rows = jdbc.sql("""
                        select z.id, z.region_id, z.name, z.runs_per_day, z.fee_std_cents, z.fee_plus_cents,
                               z.min_basket_cents, p.n, ST_Y(p.geom) as lat, ST_X(p.geom) as lng
                          from region.zones z
                          left join lateral (
                               select (d).path[1] as n, (d).geom as geom
                                 from ST_DumpPoints(ST_ExteriorRing(z.polygon::geometry)) d) p on true
                         where z.region_id = :market
                         order by z.sort, z.name, z.id, p.n
                        """)
                .param("market", marketId)
                .query((rs, _) -> new Row(
                        new Zone(
                                rs.getString("id"),
                                rs.getString("region_id"),
                                rs.getString("name"),
                                List.of(),
                                rs.getObject("runs_per_day", Integer.class),
                                rs.getObject("fee_std_cents", Long.class),
                                rs.getObject("fee_plus_cents", Long.class),
                                rs.getObject("min_basket_cents", Long.class)),
                        rs.getObject("lat") == null ? null : new Point(rs.getDouble("lat"), rs.getDouble("lng"))))
                .list();
        var zones = new LinkedHashMap<String, Zone>();
        var rings = new LinkedHashMap<String, List<Point>>();
        for (var row : rows) {
            zones.putIfAbsent(row.zone().id(), row.zone());
            var ring = rings.computeIfAbsent(row.zone().id(), _ -> new ArrayList<>());
            if (row.point() != null) {
                ring.add(row.point());
            }
        }
        return zones.values().stream()
                .map(z -> new Zone(
                        z.id(),
                        z.marketId(),
                        z.name(),
                        rings.getOrDefault(z.id(), List.of()),
                        z.runsPerDay(),
                        z.feeStdCents(),
                        z.feePlusCents(),
                        z.minBasketCents()))
                .toList();
    }

    private record Row(Zone zone, @Nullable Point point) {}
}
