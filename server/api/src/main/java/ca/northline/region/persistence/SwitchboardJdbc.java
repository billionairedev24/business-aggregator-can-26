package ca.northline.region.persistence;

import ca.northline.region.application.Switchboard.Market;
import ca.northline.region.application.Switchboard.Zone;
import ca.northline.region.application.Switchboard.ZoneInput;
import ca.northline.region.application.SwitchboardStore;
import ca.northline.region.domain.Stage;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link SwitchboardStore} over {@code region.regions}, {@code tax_profiles}, {@code zones} and {@code waitlist} (S-84). */
@Repository
@RequiredArgsConstructor
class SwitchboardJdbc implements SwitchboardStore {

    private static final String PROVINCE = """
            select r.id, r.province, r.name_i18n ->> 'en' as name_en, r.name_i18n ->> 'fr' as name_fr,
                   coalesce(r.stage, 'off') as stage, coalesce(r.languages, '{}') as languages, r.courier_model,
                   coalesce(r.time_zones, '{}') as time_zones, r.holidays, r.privacy_law, r.registries,
                   t.gst, t.pst, t.hst, t.qst, t.id as tax_id,
                   (select count(*) from region.waitlist w join region.regions m on m.id = w.region_id
                     where m.id = r.id or m.parent_id = r.id) as waitlist
              from region.regions r left join region.tax_profiles t on t.id = r.tax_profile_id
             where r.kind = 'province'""";

    private final JdbcClient jdbc;

    @Override
    public List<ProvinceRow> provinces() {
        return jdbc.sql(PROVINCE + " order by r.sort, r.id").query((rs, _) -> province(rs)).list();
    }

    @Override
    public Optional<ProvinceRow> province(String code) {
        return jdbc.sql(PROVINCE + " and r.province = :code")
                .param("code", code)
                .query((rs, _) -> province(rs))
                .optional();
    }

    @Override
    public Optional<RegionRef> lock(String regionId) {
        return jdbc.sql("""
                        select id, kind, parent_id, province, city, coalesce(stage, 'off') as stage
                          from region.regions where id = :id for update""")
                .param("id", regionId)
                .query((rs, _) -> new RegionRef(
                        rs.getString("id"),
                        rs.getString("kind"),
                        rs.getString("parent_id"),
                        rs.getString("province"),
                        rs.getString("city"),
                        CodedEnum.fromCode(Stage.class, rs.getString("stage"))))
                .optional();
    }

    @Override
    public List<Market> markets(String provinceId) {
        return jdbc.sql("""
                        select m.id, m.city, coalesce(m.stage, 'off') as stage, ST_Y(m.center::geometry) as lat,
                               ST_X(m.center::geometry) as lng, m.radius_km,
                               (select count(*) from region.zones z where z.region_id = m.id) as zones,
                               (select count(*) from region.waitlist w where w.region_id = m.id) as waitlist
                          from region.regions m
                         where m.kind = 'market' and m.parent_id = :p
                         order by m.sort, m.city, m.id""")
                .param("p", provinceId)
                .query((rs, _) -> new Market(
                        rs.getString("id"),
                        rs.getString("city"),
                        CodedEnum.fromCode(Stage.class, rs.getString("stage")),
                        rs.getObject("lat", Double.class),
                        rs.getObject("lng", Double.class),
                        decimal(rs.getBigDecimal("radius_km")),
                        rs.getInt("zones"),
                        rs.getLong("waitlist")))
                .list();
    }

    @Override
    public List<Zone> zones(String provinceId) {
        return jdbc.sql("""
                        select z.id, z.region_id, z.name, z.runs_per_day, z.fee_std_cents, z.fee_plus_cents,
                               z.min_basket_cents, round((ST_Area(z.polygon) / 1e6)::numeric, 2) as area_km2
                          from region.zones z join region.regions m on m.id = z.region_id
                         where m.parent_id = :p
                         order by m.sort, z.sort, z.name, z.id""")
                .param("p", provinceId)
                .query((rs, _) -> new Zone(
                        rs.getString("id"),
                        rs.getString("region_id"),
                        rs.getString("name"),
                        rs.getObject("runs_per_day", Integer.class),
                        rs.getObject("fee_std_cents", Long.class),
                        rs.getObject("fee_plus_cents", Long.class),
                        rs.getObject("min_basket_cents", Long.class),
                        decimal(rs.getBigDecimal("area_km2"))))
                .list();
    }

    @Override
    public Optional<ZoneRef> zone(String zoneId) {
        return jdbc.sql("""
                        select z.id, z.region_id, m.parent_id from region.zones z
                          join region.regions m on m.id = z.region_id where z.id = :id""")
                .param("id", zoneId)
                .query((rs, _) -> new ZoneRef(rs.getString("id"), rs.getString("region_id"), rs.getString("parent_id")))
                .optional();
    }

    @Override
    public void stage(String regionId, Stage stage) {
        jdbc.sql("update region.regions set stage = :s where id = :id")
                .param("s", stage.code())
                .param("id", regionId)
                .update();
    }

    @Override
    public void courierModel(String provinceId, String model) {
        jdbc.sql("update region.regions set courier_model = :m where id = :id")
                .param("m", model)
                .param("id", provinceId)
                .update();
    }

    @Override
    public boolean cityTaken(String provinceId, String city) {
        return jdbc.sql("select count(*) from region.regions where parent_id = :p and lower(city) = lower(:c)")
                        .param("p", provinceId)
                        .param("c", city)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public String insertMarket(String provinceId, String province, String city, double lat, double lng, double radiusKm) {
        var id = marketId(city);
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center, radius_km,
                                                    languages, sort)
                        select :id, 'market', p.id, :province, :city,
                               jsonb_build_object('en', cast(:city as text), 'fr', cast(:city as text)), 'off',
                               ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius, p.languages,
                               coalesce((select max(sort) from region.regions where parent_id = p.id), 0) + 1
                          from region.regions p where p.id = :parent""")
                .param("id", id)
                .param("province", province)
                .param("city", city)
                .param("lat", lat)
                .param("lng", lng)
                .param("radius", BigDecimal.valueOf(radiusKm))
                .param("parent", provinceId)
                .update();
        return id;
    }

    /** {@code mkt-<city slug>}, with a short suffix when taken (ids are stable references in other modules' rows). */
    private String marketId(String city) {
        var slug = Normalizer.normalize(city, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        var id = "mkt-" + (slug.isEmpty() ? Ids.next().toLowerCase(Locale.ROOT) : slug);
        var taken = jdbc.sql("select count(*) from region.regions where id = :id").param("id", id).query(Long.class).single() > 0;
        return taken ? id + "-" + Ids.next().substring(20).toLowerCase(Locale.ROOT) : id;
    }

    @Override
    public String saveZone(@Nullable String zoneId, ZoneInput zone) {
        var id = zoneId == null ? Ids.next() : zoneId;
        var polygon = zone.boundary() == null
                ? null
                : "ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(coalesce(cast(:boundary as jsonb) -> 'geometry', cast(:boundary as jsonb))::text), 4326)), 3))";
        // region.zones.polygon is a geography(Polygon): keep the largest polygon of a valid result
        var geography = polygon == null ? null : """
                (select (d).geom::geography from (select ST_Dump(%s) d) x
                  order by ST_Area((d).geom) desc limit 1)""".formatted(polygon);
        try {
            if (zoneId == null) {
                jdbc.sql("""
                                insert into region.zones (id, region_id, name, polygon, runs_per_day, fee_std_cents,
                                                          fee_plus_cents, min_basket_cents, sort)
                                values (:id, :m, :name, %s, :runs, :fee, :plus, :min,
                                        coalesce((select max(sort) from region.zones where region_id = :m), 0) + 1)"""
                                .formatted(geography == null ? "null" : geography))
                        .param("id", id)
                        .param("m", zone.marketId())
                        .param("name", zone.name())
                        .param("runs", zone.runsPerDay(), java.sql.Types.INTEGER)
                        .param("fee", zone.feeStdCents(), java.sql.Types.BIGINT)
                        .param("plus", zone.feePlusCents(), java.sql.Types.BIGINT)
                        .param("min", zone.minBasketCents(), java.sql.Types.BIGINT)
                        .param("boundary", zone.boundary(), java.sql.Types.VARCHAR)
                        .update();
            } else {
                jdbc.sql("""
                                update region.zones set region_id = :m, name = :name, runs_per_day = :runs,
                                       fee_std_cents = :fee, fee_plus_cents = :plus, min_basket_cents = :min%s
                                 where id = :id"""
                                .formatted(geography == null ? "" : ", polygon = " + geography))
                        .param("id", id)
                        .param("m", zone.marketId())
                        .param("name", zone.name())
                        .param("runs", zone.runsPerDay(), java.sql.Types.INTEGER)
                        .param("fee", zone.feeStdCents(), java.sql.Types.BIGINT)
                        .param("plus", zone.feePlusCents(), java.sql.Types.BIGINT)
                        .param("min", zone.minBasketCents(), java.sql.Types.BIGINT)
                        .param("boundary", zone.boundary(), java.sql.Types.VARCHAR)
                        .update();
            }
        } catch (DataAccessException e) {
            if (zone.boundary() != null) {
                throw new IllegalArgumentException("boundary", e);
            }
            throw e;
        }
        return id;
    }

    @Override
    public void deleteZone(String zoneId) {
        jdbc.sql("delete from region.zones where id = :id").param("id", zoneId).update();
    }

    private static ProvinceRow province(ResultSet rs) throws SQLException {
        var names = new LinkedHashMap<String, String>();
        names.put("en", rs.getString("name_en"));
        var fr = rs.getString("name_fr");
        if (fr != null) {
            names.put("fr", fr);
        }
        var tax = new LinkedHashMap<String, Integer>();
        if (rs.getString("tax_id") != null) {
            for (var kind : List.of("gst", "pst", "hst", "qst")) {
                var rate = rs.getBigDecimal(kind);
                if (rate != null) {
                    tax.put(kind, rate.movePointRight(4).intValue());
                }
            }
        }
        return new ProvinceRow(
                rs.getString("id"),
                rs.getString("province"),
                Map.copyOf(names),
                CodedEnum.fromCode(Stage.class, rs.getString("stage")),
                texts(rs, "languages"),
                rs.getString("courier_model"),
                Map.copyOf(tax),
                texts(rs, "time_zones"),
                texts(rs, "holidays"),
                rs.getString("privacy_law"),
                texts(rs, "registries"),
                rs.getLong("waitlist"));
    }

    private static List<String> texts(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static @Nullable Double decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
