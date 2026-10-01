package ca.northline.worker.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Recipients} as a read-only view of the api's tables (the worker and the api share the database, as auth
 * does): {@code merchants.merchant_members} + {@code merchants.merchants}, {@code identity.users} (the same columns and
 * "active accounts only" rule as the api's {@code identity.api.NotificationContacts}) and
 * {@code messaging.notification_prefs}. It never writes to them. Chosen over an api read model over HTTP: no service
 * credentials between the two apps, no extra hop per event, and the queries stay next to the only code that needs
 * contact details at send time — events keep carrying ids only.
 */
public final class JdbcRecipients implements Recipients {

    private static final TypeReference<Map<String, Map<String, Boolean>>> MATRIX = new TypeReference<>() {};

    private static final String SELECT = """
            select u.id, m.role,
                   coalesce(nullif(trim(concat_ws(' ', u.first_name, u.last_name)), ''), u.display_name, '') as name,
                   u.email::text as email, u.phone, u.locale,
                   p.matrix::text as matrix, p.quiet_from, p.quiet_to,
                   coalesce((select mk.time_zones[1] from region.regions mk
                              where mk.kind = 'market' and lower(mk.city) = lower(b.city)
                                and mk.province = coalesce(b.province, :defaultProvince)
                              order by mk.sort limit 1),
                            (select pv.time_zones[1] from region.regions pv
                              where pv.kind = 'province' and pv.province = coalesce(b.province, :defaultProvince)),
                            :platformZone) as zone
              from merchants.merchant_members m
              join merchants.merchants b on b.id = m.merchant_id
              join identity.users u on u.id = m.user_id
              left join messaging.notification_prefs p on p.user_id = u.id
             where m.merchant_id = :merchant and coalesce(u.status, 'active') = 'active'
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Preferences.Defaults defaults;
    private final RegionSettings region;

    /**
     * Quiet hours are kept in the business's own zone, read from the region model's rows (S-134): its market's, else
     * its province's (the configured default province when it has none), else the platform zone.
     *
     * @param defaultProvince {@code northline.region.default-province}; platformZone {@code
     *     northline.region.platform-zone}
     */
    public record RegionSettings(String defaultProvince, ZoneId platformZone) {}

    public JdbcRecipients(JdbcClient jdbc, JsonMapper json, Preferences.Defaults defaults, RegionSettings region) {
        this.jdbc = jdbc;
        this.json = json;
        this.defaults = defaults;
        this.region = region;
    }

    @Override
    public List<Recipient> of(String merchantId, Set<String> roles) {
        return jdbc.sql(SELECT + " and m.role in (:roles) order by m.user_id")
                .param("merchant", merchantId)
                .param("defaultProvince", region.defaultProvince())
                .param("platformZone", region.platformZone().getId())
                .param("roles", List.copyOf(roles))
                .query((rs, _) -> recipient(rs))
                .list();
    }

    @Override
    public Optional<Recipient> member(String merchantId, String userId) {
        return jdbc.sql(SELECT + " and m.user_id = :user")
                .param("merchant", merchantId)
                .param("defaultProvince", region.defaultProvince())
                .param("platformZone", region.platformZone().getId())
                .param("user", userId)
                .query((rs, _) -> recipient(rs))
                .optional();
    }

    @Override
    public Optional<String> businessName(String merchantId) {
        return jdbc.sql("select display_name from merchants.merchants where id = :id")
                .param("id", merchantId)
                .query(String.class)
                .optional();
    }

    private Recipient recipient(ResultSet rs) throws SQLException {
        var locale = rs.getString("locale");
        var matrix = rs.getString("matrix");
        var from = rs.getObject("quiet_from", LocalTime.class);
        var to = rs.getObject("quiet_to", LocalTime.class);
        var zone = ZoneId.of(rs.getString("zone"));
        var preferences = matrix == null && from == null && to == null
                ? defaults.only(zone)
                : defaults.with(
                        matrix == null ? Map.of() : json.readValue(matrix, MATRIX),
                        from == null ? defaults.quietFrom() : from,
                        to == null ? defaults.quietTo() : to,
                        zone);
        return new Recipient(
                rs.getString("id"),
                rs.getString("role"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-CA" : locale.strip()),
                preferences);
    }
}
