package ca.northline.trust.persistence;

import ca.northline.trust.api.QualityQuery.Component;
import ca.northline.trust.api.QualityQuery.QualityScore;
import ca.northline.trust.application.QualityStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The latest {@code trust.quality_scores} row. {@code components} is
 * {@code {"on_time":{"value":98,"floor":95}, …, "disputes":{"value":0.3,"floor":1}}}; components are returned in the
 * dashboard's order and the dispute rate is inverted onto the 0–100 bar (100 − 10 × rate).
 */
@Repository
@RequiredArgsConstructor
class TrustQualityAdapter implements QualityStore {

    static final List<String> ORDER = List.of("on_time", "photos", "response", "rebook", "disputes");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public Optional<QualityScore> latest(String merchantId) {
        return jdbc.sql("""
                        select merchant_id, date, score, components::text as components
                          from trust.quality_scores where merchant_id = :m order by date desc limit 1
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new QualityScore(
                        rs.getString("merchant_id"),
                        rs.getObject("date", LocalDate.class),
                        rs.getInt("score"),
                        components(rs.getString("components"))))
                .optional();
    }

    static List<Component> components(@Nullable String json) {
        if (json == null) {
            return List.of();
        }
        Map<String, Map<String, Double>> raw = JSON.readValue(json, new TypeReference<>() {});
        return ORDER.stream()
                .filter(raw::containsKey)
                .map(key -> {
                    var c = raw.getOrDefault(key, Map.of());
                    double value = c.getOrDefault("value", 0d);
                    double floor = c.getOrDefault("floor", 0d);
                    boolean inverted = "disputes".equals(key);
                    return new Component(key, value, floor, bar(value, inverted), bar(floor, inverted), inverted);
                })
                .toList();
    }

    private static int bar(double value, boolean inverted) {
        return (int) Math.round(Math.clamp(inverted ? 100 - 10 * value : value, 0, 100));
    }
}
