package ca.northline.region.persistence;

import ca.northline.region.application.MarketStore;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.Stage;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketStore} on {@code region.regions} / {@code region.zones} (PostGIS geography) and {@code region.waitlist}. */
@Repository
@RequiredArgsConstructor
class MarketQueries implements MarketStore {

    private static final String REGION = """
            select id, kind, parent_id, province, city, name_i18n ->> 'en' as name_en, name_i18n ->> 'fr' as name_fr,
                   coalesce(stage, 'off') as stage,
                   ST_Y(center::geometry) as lat, ST_X(center::geometry) as lng
              from region.regions
            """;
    private static final String POINT = "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography";

    private final JdbcClient jdbc;

    @Override
    public List<RegionRow> regions() {
        return jdbc.sql(REGION + " order by kind desc, sort, id")
                .query((rs, _) -> region(rs))
                .list();
    }

    @Override
    public Optional<RegionRow> marketAt(GeoPoint point) {
        return jdbc.sql(REGION + " where kind = 'market' and ST_DWithin(center, " + POINT + ", radius_km * 1000)"
                        + " order by ST_Distance(center, " + POINT + ") limit 1")
                .param("lat", point.lat())
                .param("lng", point.lng())
                .query((rs, _) -> region(rs))
                .optional();
    }

    @Override
    public Optional<RegionRow> nearestMarket(String province, GeoPoint point) {
        return jdbc.sql(REGION + " where kind = 'market' and province = :province" + " order by ST_Distance(center, "
                        + POINT + ") limit 1")
                .param("province", province.toUpperCase(java.util.Locale.ROOT))
                .param("lat", point.lat())
                .param("lng", point.lng())
                .query((rs, _) -> region(rs))
                .optional();
    }

    @Override
    public Optional<RegionRow> province(String code) {
        return jdbc.sql(REGION + " where kind = 'province' and province = :province")
                .param("province", code.toUpperCase(java.util.Locale.ROOT))
                .query((rs, _) -> region(rs))
                .optional();
    }

    @Override
    public Optional<RegionRow> region(String id) {
        return jdbc.sql(REGION + " where id = :id")
                .param("id", id)
                .query((rs, _) -> region(rs))
                .optional();
    }

    @Override
    public Optional<ZoneRow> zoneAt(String marketId, GeoPoint point) {
        return jdbc.sql("""
                        select id, region_id, name, runs_per_day, fee_std_cents, fee_plus_cents, min_basket_cents
                          from region.zones
                         where region_id = :market and ST_Covers(polygon, %s)
                         order by sort, id limit 1
                        """.formatted(POINT))
                .param("market", marketId)
                .param("lat", point.lat())
                .param("lng", point.lng())
                .query((rs, _) -> new ZoneRow(
                        rs.getString("id"),
                        rs.getString("region_id"),
                        rs.getString("name"),
                        rs.getObject("runs_per_day", Integer.class),
                        rs.getObject("fee_std_cents", Long.class),
                        rs.getObject("fee_plus_cents", Long.class),
                        rs.getObject("min_basket_cents", Long.class)))
                .optional();
    }

    @Override
    public boolean joinWaitlist(
            String id, String regionId, @Nullable String userId, @Nullable String email, String locale) {
        var conflict = userId != null
                ? "(region_id, user_id) where user_id is not null"
                : "(region_id, lower(email)) where user_id is null";
        return jdbc.sql("""
                        insert into region.waitlist (id, region_id, user_id, email, locale)
                        values (:id, :region, :user, :email, :locale)
                        on conflict %s do nothing
                        """.formatted(conflict))
                        .param("id", id)
                        .param("region", regionId)
                        .param("user", userId, java.sql.Types.VARCHAR)
                        .param("email", email, java.sql.Types.VARCHAR)
                        .param("locale", locale)
                        .update()
                > 0;
    }

    private static RegionRow region(ResultSet rs) throws SQLException {
        return new RegionRow(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("parent_id"),
                rs.getString("province"),
                rs.getString("city"),
                rs.getString("name_en"),
                rs.getString("name_fr"),
                CodedEnum.fromCode(Stage.class, rs.getString("stage")),
                rs.getObject("lat", Double.class),
                rs.getObject("lng", Double.class));
    }
}
