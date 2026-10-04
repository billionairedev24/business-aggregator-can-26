package ca.northline.region.persistence;

import ca.northline.region.api.AgeClass;
import ca.northline.region.api.AgeRules;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AgeRules} over {@code region.age_rules} (V340). Read on each call: a checkout asks once. */
@Repository
@RequiredArgsConstructor
class AgeRuleQueries implements AgeRules {

    private static final String SELECT = """
            select province, age_class, minimum_age, delivery_allowed, pickup_allowed, delivery_from, delivery_until,
                   pickup_from, pickup_until, source, confirmed
              from region.age_rules
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<AgeRule> rule(@Nullable String province, AgeClass ageClass) {
        if (province == null || province.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " where province = :p and age_class = :c")
                .param("p", province.strip().toUpperCase(Locale.ROOT))
                .param("c", ageClass.code())
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public List<AgeRule> all() {
        return jdbc.sql(SELECT + " order by province, age_class")
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public int highestMinimumAge() {
        return jdbc.sql("select coalesce(max(minimum_age), 0) from region.age_rules")
                .query(Integer.class)
                .single();
    }

    private static AgeRule map(ResultSet rs) throws SQLException {
        return new AgeRule(
                rs.getString("province"),
                CodedEnum.fromCode(AgeClass.class, rs.getString("age_class")),
                rs.getInt("minimum_age"),
                rs.getBoolean("delivery_allowed"),
                rs.getBoolean("pickup_allowed"),
                time(rs, "delivery_from"),
                time(rs, "delivery_until"),
                time(rs, "pickup_from"),
                time(rs, "pickup_until"),
                rs.getString("source"),
                rs.getBoolean("confirmed"));
    }

    private static @Nullable LocalTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalTime.class);
    }
}
