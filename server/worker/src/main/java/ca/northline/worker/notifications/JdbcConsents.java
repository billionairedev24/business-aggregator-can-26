package ca.northline.worker.notifications;

import ca.northline.email.CommercialConsent;
import ca.northline.email.MessageClasses.ConsentCategory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The worker's {@link CommercialConsent} (S-108): the newest of the person's {@code messaging.consent_records} for the
 * category — the api writes them, the worker only reads, right before a commercial message goes (so a consent withdrawn
 * after a notice was queued or held back by quiet hours stops it).
 */
final class JdbcConsents implements CommercialConsent {

    private final JdbcClient jdbc;

    JdbcConsents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean allows(String userId, ConsentCategory category) {
        return jdbc.sql("""
                        select action = 'granted' from messaging.consent_records
                         where user_id = :u and category = :c order by at desc, id desc limit 1
                        """)
                .param("u", userId)
                .param("c", category.code())
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }
}
