package ca.northline.tools.demo;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The market the demo lives in — region data, never a city in code (S-134): {@code DEMO_MARKET} (a market id) when
 * set, else the first market of the configured default province ({@code REGION_DEFAULT_PROVINCE}, then S-44's {@code
 * SEARCH_DEFAULT_MARKET}, as the api reads them), else of the first live province. The seed sets it {@code live} (a
 * pilot market takes no orders) and gives it delivery and service zones when it has none, laid out around its centre.
 */
record DemoMarket(String id, String province, String city, double lat, double lng, double radiusKm, ZoneId zone) {

    /** The neighbourhood-free zone names the seed draws around the centre when the market has none. */
    static final List<String> QUARTERS = List.of("Central", "North", "East", "South", "West");

    static DemoMarket resolve(Db db, Map<String, String> env) {
        var wanted = env.getOrDefault("DEMO_MARKET", "").strip();
        var province = firstNonBlank(env.get("REGION_DEFAULT_PROVINCE"), env.get("SEARCH_DEFAULT_MARKET"));
        String id;
        if (!wanted.isEmpty()) {
            id = db.text("select id from region.regions where kind = 'market' and id = ?", wanted)
                    .orElseThrow(() ->
                            new IllegalStateException("DEMO_MARKET " + wanted + " is not a market in region.regions"));
        } else {
            if (province.isEmpty()) {
                province = db.text(
                                "select province from region.regions where kind = 'province' and stage = 'live' order by sort, id limit 1")
                        .orElseThrow(() -> new IllegalStateException(
                                "no live province in region.regions; set REGION_DEFAULT_PROVINCE or DEMO_MARKET"));
            }
            var p = province.toUpperCase(Locale.ROOT);
            id = db.text(
                            "select id from region.regions where kind = 'market' and province = ? order by sort, id limit 1",
                            p)
                    .orElseThrow(() -> new IllegalStateException(
                            "province " + p + " has no market in region.regions; set DEMO_MARKET"));
        }
        var rows = db.list(
                """
                select m.id, m.province, m.city, ST_Y(m.center::geometry) as lat, ST_X(m.center::geometry) as lng, m.radius_km,
                       coalesce(m.time_zones[1], p.time_zones[1], 'UTC') as tz
                  from region.regions m left join region.regions p on p.id = m.parent_id
                 where m.id = ?
                """,
                rs -> new DemoMarket(
                        rs.getString("id"),
                        rs.getString("province").strip(),
                        rs.getString("city"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getDouble("radius_km"),
                        ZoneId.of(rs.getString("tz"))),
                id);
        return rows.getFirst();
    }

    /** Sets the market (and its province) live and gives it zones when it has none. Returns what changed. */
    String prepare(Db db) {
        var was = db.text("select stage from region.regions where id = ?", id).orElse("?");
        db.update("update region.regions set stage = 'live' where id = ?", id);
        db.update(
                "update region.regions set stage = 'live' where kind = 'province' and province = ? and stage <> 'live'",
                province);
        var delivery = db.count("select count(*) from region.zones where region_id = ?", id);
        if (delivery == 0) {
            for (int i = 0; i < QUARTERS.size(); i++) {
                db.update(
                        """
                        insert into region.zones (id, region_id, name, polygon, runs_per_day, fee_std_cents, fee_plus_cents, min_basket_cents, sort)
                        values (?, ?, ?, ST_GeogFromText(?), 2, 499, 0, 0, ?) on conflict (id) do nothing
                        """,
                        DemoIds.of("zone:" + id + ":" + i),
                        id,
                        QUARTERS.get(i),
                        "SRID=4326;" + quarterPolygon(i),
                        i + 1);
            }
        }
        var service = db.count("select count(*) from availability.service_zones where market_id = ?", id);
        if (service == 0) {
            for (int i = 0; i < QUARTERS.size(); i++) {
                var c = quarterCentre(i);
                db.update(
                        """
                        insert into availability.service_zones (name, city, centre, area, radius_m, market_id, sort, default_on)
                        values (?, ?, ST_GeogFromText(?), ST_GeogFromText(?), ?, ?, ?, true) on conflict do nothing
                        """,
                        serviceZoneName(i),
                        city,
                        point(c[0], c[1]),
                        "SRID=4326;" + quarterPolygon(i),
                        (int) Math.round(radiusKm * 1000 / 2),
                        id,
                        i + 1);
            }
        }
        return "was " + was.strip() + ", now live" + (delivery == 0 ? "; delivery zones added" : "")
                + (service == 0 ? "; service zones added" : "");
    }

    /** The service zones providers cover: the market's own (default ones first), else the seed's. */
    List<String> serviceZones(Db db) {
        return db.list(
                "select name from availability.service_zones where market_id = ? order by default_on desc, sort, name",
                rs -> rs.getString(1),
                id);
    }

    /** The delivery zone a point falls in, if any. */
    Optional<String> deliveryZoneAt(Db db, double lat, double lng) {
        return db.text(
                "select id from region.zones where region_id = ? and ST_Covers(polygon, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) order by sort limit 1",
                id,
                lng,
                lat);
    }

    private String serviceZoneName(int i) {
        return city + " " + QUARTERS.get(i);
    }

    /** A point {@code north} km north and {@code east} km east of the centre. */
    double[] offset(double northKm, double eastKm) {
        var dLat = northKm / 111.32;
        var dLng = eastKm / (111.32 * Math.cos(Math.toRadians(lat)));
        return new double[] {lat + dLat, lng + dLng};
    }

    /** Somewhere inside the market, deterministic for a key: within half its radius of the centre. */
    double[] pointFor(String key) {
        var h = Math.floorMod(key.hashCode(), Integer.MAX_VALUE);
        var angle = (h % 360) * Math.PI / 180;
        var dist = (0.1 + (h / 360 % 100) / 100.0 * 0.4) * Math.min(radiusKm, 20);
        return offset(Math.sin(angle) * dist, Math.cos(angle) * dist);
    }

    private double[] quarterCentre(int i) {
        var r = Math.min(radiusKm, 20) / 2;
        return switch (i) {
            case 0 -> new double[] {lat, lng};
            case 1 -> offset(r, 0);
            case 2 -> offset(0, r);
            case 3 -> offset(-r, 0);
            default -> offset(0, -r);
        };
    }

    /** Central is a square of half the radius around the centre; the others the four sides around it, out to the radius. */
    private String quarterPolygon(int i) {
        var r = Math.max(radiusKm, 5);
        var h = r / 4;
        double[][] box = switch (i) {
            case 0 -> new double[][] {{-h, -h}, {h, h}};
            case 1 -> new double[][] {{h, -r}, {r, r}};
            case 2 -> new double[][] {{-h, h}, {h, r}};
            case 3 -> new double[][] {{-r, -r}, {-h, r}};
            default -> new double[][] {{-h, -r}, {h, -h}};
        };
        var sw = offset(box[0][0], box[0][1]);
        var ne = offset(box[1][0], box[1][1]);
        return String.format(
                Locale.ROOT,
                "POLYGON((%.6f %.6f,%.6f %.6f,%.6f %.6f,%.6f %.6f,%.6f %.6f))",
                sw[1],
                sw[0],
                ne[1],
                sw[0],
                ne[1],
                ne[0],
                sw[1],
                ne[0],
                sw[1],
                sw[0]);
    }

    static String point(double lat, double lng) {
        return String.format(Locale.ROOT, "SRID=4326;POINT(%.6f %.6f)", lng, lat);
    }

    private static String firstNonBlank(@Nullable String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }
}
