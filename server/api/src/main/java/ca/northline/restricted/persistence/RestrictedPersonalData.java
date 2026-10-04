package ca.northline.restricted.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, restricted: the customer's age check ("verified over N, on date, by method" — nothing else is kept) and the ID
 * checks a courier or team member recorded at handoffs (what they confirmed; no ID data). On erasure the age check is
 * deleted (nothing obliges us to keep it; the person verifies again if they come back). The handoff checks hold no
 * customer data; a courier's or team member's own checks stay under their id as evidence that restricted goods were
 * handed over lawfully (two years, retention category {@code restricted.handoff_checks}).
 */
@Component
@RequiredArgsConstructor
class RestrictedPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "restricted";
    }

    @Override
    public List<Section> export(Subject subject) {
        var who = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "restricted.age_verifications", "Your age check", "Votre vérification d’âge", """
                                select state, over_age, verified_on, method, last_error, attempts, started_at, updated_at
                                  from restricted.age_verifications where user_id = :u
                                """, who),
                section(
                        jdbc,
                        "restricted.handoff_checks",
                        "ID checks you recorded at handoffs",
                        "Vérifications d’identité que vous avez inscrites à la remise",
                        """
                                select order_id, place, outcome, id_checked, recipient_matches, of_age, reason, checked_at
                                  from restricted.handoff_checks where actor_id = :u order by checked_at
                                """,
                        who));
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from restricted.age_verifications where user_id = :u")
                .param("u", subject.userId())
                .update();
        var checks = jdbc.sql("select count(*) from restricted.handoff_checks where actor_id = :u")
                .param("u", subject.userId())
                .query(Long.class)
                .single();
        return checks > 0
                ? Erasure.done().retaining("restricted.handoff_checks", Retention.AGE_CHECK_RECORDS)
                : Erasure.done();
    }
}
