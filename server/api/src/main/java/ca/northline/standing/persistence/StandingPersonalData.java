package ca.northline.standing.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, standing (S-156): a customer's no-show consequences, and the appeals a person filed for their business.
 * Erasure removes a customer's consequences (their warning is identity's and goes with the account); appeals stay — they
 * are the business's record of a decision about it — with the filer's id only (identity blanks the account).
 */
@Component
@RequiredArgsConstructor
class StandingPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "standing";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.<String, Object>of("u", subject.userId());
        return List.of(
                section(
                        jdbc,
                        "standing.consequences",
                        "Your standing with Northline",
                        "Votre dossier auprès de Northline",
                        """
                        select kind, cause, reason, started_at, state, lifted_at, lift_reason
                          from standing.consequences where customer_id = :u order by started_at
                        """,
                        p),
                section(jdbc, "standing.appeals", "Appeals you filed", "Appels que vous avez déposés", """
                        select number, subject_type, subject_kind, statement, state, filed_at, decided_at,
                               decision_reason
                          from standing.appeals where filed_by = :u order by filed_at
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        jdbc.sql("delete from standing.consequences where customer_id = :u")
                .param("u", u)
                .update();
        var filed = jdbc.sql("select exists (select 1 from standing.appeals where filed_by = :u)")
                .param("u", u)
                .query(Boolean.class)
                .single();
        return filed ? Erasure.done().retaining("standing.appeals", Retention.BUSINESS_RECORDS) : Erasure.done();
    }
}
