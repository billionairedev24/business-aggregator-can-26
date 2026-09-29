package ca.northline.trust.persistence;

import ca.northline.trust.api.Reputation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Minimal {@link Reputation} added by the operations workstream (docs/DECISIONS.md "Operations"). */
@Repository
@RequiredArgsConstructor
class ReputationQueries implements Reputation {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    @Override
    public Optional<Rating> rating(String merchantId) {
        return jdbc.sql("""
                        select avg(rating) as average, count(*) as count from trust.reviews
                         where target_type = 'merchant' and target_id = :merchantId and rating is not null
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> {
                    long count = rs.getLong("count");
                    return count == 0
                            ? Optional.<Rating>empty()
                            : Optional.of(
                                    new Rating(rs.getBigDecimal("average").setScale(1, RoundingMode.HALF_UP), count));
                })
                .single();
    }

    @Override
    public Optional<QualityScore> latestQuality(String merchantId) {
        return jdbc.sql("""
                        select date, score, components::text as components from trust.quality_scores
                         where merchant_id = :merchantId order by date desc limit 1
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> new QualityScore(
                        rs.getDate("date").toLocalDate(), rs.getInt("score"), components(rs.getString("components"))))
                .optional();
    }

    private Map<String, BigDecimal> components(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        var out = new TreeMap<String, BigDecimal>();
        json.readTree(raw).properties().forEach(e -> {
            if (e.getValue().isNumber()) {
                out.put(e.getKey(), e.getValue().decimalValue());
            }
        });
        return Map.copyOf(out);
    }
}
