package ca.northline.auth.clients;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * S-158: partners registered in the platform console ({@code developer.partner_clients}, written by the api) with the
 * businesses whose owners approved their access ({@code developer.partner_bindings}). Read-only here: the api owns the
 * tables, northline-auth turns each row into the OAuth client {@code partner:<name>} ({@link OAuthClientSync}).
 */
final class ConsolePartners {

    /** One console partner as a {@link PartnerSpec} plus what configuration doesn't have. */
    record Row(String name, PartnerSpec spec, int keyVersion) {
        String clientId() {
            return PartnerSpec.clientId(name);
        }
    }

    private final JdbcOperations jdbc;

    ConsolePartners(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /** Every console partner (disabled ones as revoked), oldest first; empty before the api's V485 ran. */
    List<Row> all() {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject("select to_regclass('developer.partner_clients') is not null", Boolean.class))) {
            return List.of();
        }
        var approved = new LinkedHashMap<String, List<String>>();
        jdbc.query("""
                select partner_id, merchant_id from developer.partner_bindings
                where state = 'approved' order by partner_id, merchant_id""", rs -> {
            approved.computeIfAbsent(rs.getString(1), _ -> new java.util.ArrayList<>())
                    .add(Objects.requireNonNull(rs.getString(2)));
        });
        return jdbc.query("""
                select id, name, display_name, jwk_set_url, scopes, token_ttl_seconds, state, key_version
                from developer.partner_clients order by created_at, id""", (rs, _) -> {
            var id = Objects.requireNonNull(rs.getString("id"));
            var spec = new PartnerSpec(
                    rs.getString("display_name"),
                    rs.getString("jwk_set_url"),
                    List.of(),
                    strings(rs.getArray("scopes")),
                    approved.getOrDefault(id, List.of()),
                    Duration.ofSeconds(rs.getInt("token_ttl_seconds")),
                    "disabled".equals(rs.getString("state")));
            return new Row(Objects.requireNonNull(rs.getString("name")), spec, rs.getInt("key_version"));
        });
    }

    /** By client id (for the sync's "not in configuration" report). */
    Map<String, Row> byClientId() {
        var out = new LinkedHashMap<String, Row>();
        all().forEach(r -> out.put(r.clientId(), r));
        return out;
    }

    private static List<String> strings(Array array) throws SQLException {
        var values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }
}
