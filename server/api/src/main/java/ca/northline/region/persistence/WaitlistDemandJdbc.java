package ca.northline.region.persistence;

import ca.northline.region.api.WaitlistDemand;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link WaitlistDemand}: {@code region.waitlist} rows counted by their region's province. */
@Repository
@RequiredArgsConstructor
class WaitlistDemandJdbc implements WaitlistDemand {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Long> byProvince() {
        var out = new HashMap<String, Long>();
        jdbc.sql("""
                        select r.province, count(*) from region.waitlist w join region.regions r on r.id = w.region_id
                         where r.province is not null group by r.province""").query(rs -> {
            out.put(Objects.requireNonNull(rs.getString(1)).strip(), rs.getLong(2));
        });
        return out;
    }
}
