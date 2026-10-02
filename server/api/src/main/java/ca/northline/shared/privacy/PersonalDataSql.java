package ca.northline.shared.privacy;

import ca.northline.shared.privacy.PersonalDataContributor.Section;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL helpers for {@link PersonalDataContributor} implementations. */
public final class PersonalDataSql {

    private PersonalDataSql() {}

    /**
     * One export section from a {@code SELECT} over the module's own tables: Postgres renders the rows as a JSON array
     * (timestamps ISO-8601, {@code jsonb} as nested JSON, arrays as arrays), so every column the query names is in
     * the export exactly as stored.
     */
    public static Section section(
            JdbcClient jdbc, String key, String titleEn, String titleFr, String select, Map<String, ?> params) {
        return jdbc.sql("select coalesce(json_agg(t), '[]')::text as rows, count(*) as n from (" + select + ") t")
                .params(params)
                .query((rs, _) -> new Section(key, titleEn, titleFr, rs.getString("rows"), rs.getInt("n")))
                .single();
    }

    /**
     * Lets the current transaction blank or correct what an immutability trigger otherwise protects (V272: a verified
     * review's author name and words). Transaction-local.
     */
    public static void allowPrivacyChange(JdbcClient jdbc) {
        jdbc.sql("select set_config('northline.privacy_change', 'on', true)")
                .query(String.class)
                .single();
    }
}
