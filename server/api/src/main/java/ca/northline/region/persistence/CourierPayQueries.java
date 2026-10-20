package ca.northline.region.persistence;

import ca.northline.region.api.CourierPayRules;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link CourierPayRules} on {@code region.regions.courier_pay} (V435): the province's row, then the market's. */
@Repository
@RequiredArgsConstructor
class CourierPayQueries implements CourierPayRules {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public Map<String, Long> forMarket(String market) {
        var rows = jdbc.sql("""
                        select p.courier_pay::text as province_pay, m.courier_pay::text as market_pay
                          from region.regions m
                          left join region.regions p on p.kind = 'province' and p.province = m.province
                         where m.kind = 'market' and lower(m.city) = lower(:city)
                         order by m.id limit 1
                        """)
                .param("city", market.strip())
                .query((rs, _) -> new Pay(rs.getString("province_pay"), rs.getString("market_pay")))
                .list();
        var out = new LinkedHashMap<String, Long>();
        if (!rows.isEmpty()) {
            put(out, rows.getFirst().province());
            put(out, rows.getFirst().market());
        }
        return Map.copyOf(out);
    }

    private record Pay(@Nullable String province, @Nullable String market) {}

    private static void put(Map<String, Long> out, @Nullable String json) {
        if (json == null) {
            return;
        }
        JsonNode node = JSON.readTree(json);
        for (var key : KEYS) {
            var v = node.path(key);
            if (v.isIntegralNumber() && v.asLong() >= 0) {
                out.put(key, v.asLong());
            }
        }
    }
}
