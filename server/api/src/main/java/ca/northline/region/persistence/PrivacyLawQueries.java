package ca.northline.region.persistence;

import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.application.PrivacyLawStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code region.privacy_laws} (V271). */
@Repository
@RequiredArgsConstructor
class PrivacyLawQueries implements PrivacyLawStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<LawRow> law(PrivacyLaw law) {
        return jdbc.sql("""
                        select name_i18n->>'en' as name_en, name_i18n->>'fr' as name_fr,
                               short_i18n->>'en' as short_en, short_i18n->>'fr' as short_fr,
                               authority_i18n->>'en' as authority_en, authority_i18n->>'fr' as authority_fr,
                               authority_url, response_days, business_days, extension_days
                          from region.privacy_laws where code = :code
                        """)
                .param("code", law.code())
                .query((rs, _) -> new LawRow(
                        rs.getString("name_en"),
                        rs.getString("name_fr"),
                        rs.getString("short_en"),
                        rs.getString("short_fr"),
                        rs.getString("authority_en"),
                        rs.getString("authority_fr"),
                        rs.getString("authority_url"),
                        rs.getInt("response_days"),
                        rs.getBoolean("business_days"),
                        rs.getInt("extension_days")))
                .optional();
    }
}
