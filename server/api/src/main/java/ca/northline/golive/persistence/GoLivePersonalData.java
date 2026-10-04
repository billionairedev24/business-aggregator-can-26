package ca.northline.golive.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, golive (S-118): a staff member's part in a market's go-live — the gates they recorded, the launches they asked
 * for or decided, the rollbacks, their hypercare days. On erasure their free text goes; the ids stay, as in the audit
 * log they mirror (who switched a market live is an accountability record).
 */
@Component
@RequiredArgsConstructor
class GoLivePersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "golive";
    }

    @Override
    public List<Section> export(Subject subject) {
        var who = Map.of("u", subject.userId());
        return List.of(
                section(
                        jdbc,
                        "golive.gate_records",
                        "Go-live gates you recorded",
                        "Points de mise en service consignés",
                        """
                                select market_id, gate, status, evidence, evidence_url, source, recorded_at
                                  from golive.gate_records where recorded_by = :u order by recorded_at
                                """,
                        who),
                section(
                        jdbc,
                        "golive.launch_requests",
                        "Market launches you asked for or decided",
                        "Mises en service demandées ou tranchées",
                        """
                                select market_id, state, requested_at, note, override, override_reason, decided_at,
                                       decision_note, requested_by = :u as requested
                                  from golive.launch_requests where requested_by = :u or decided_by = :u
                                 order by requested_at
                                """,
                        who),
                section(
                        jdbc,
                        "golive.market_events",
                        "Launches and rollbacks",
                        "Mises en service et retours",
                        """
                                select market_id, kind, reason, occurred_at from golive.market_events
                                 where actor_id = :u order by occurred_at
                                """,
                        who),
                section(
                        jdbc,
                        "golive.hypercare_days",
                        "Your hypercare days",
                        "Vos journées d’hypersurveillance",
                        """
                                select market_id, day, primary_user_id = :u as primary_on_call,
                                       secondary_user_id = :u as secondary_on_call, business_user_id = :u as business
                                  from golive.hypercare_days
                                 where :u in (primary_user_id, secondary_user_id, business_user_id) order by day
                                """,
                        who));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        jdbc.sql("update golive.gate_records set evidence = '[erased]', evidence_url = null where recorded_by = :u")
                .param("u", u)
                .update();
        jdbc.sql("""
                        update golive.launch_requests
                           set note = case when requested_by = :u then null else note end,
                               override_reason = case when requested_by = :u and override then '[erased: override reason]'
                                                      else override_reason end,
                               decision_note = case when decided_by = :u then null else decision_note end
                         where requested_by = :u or decided_by = :u
                        """).param("u", u).update();
        jdbc.sql("update golive.market_events set reason = null where actor_id = :u")
                .param("u", u)
                .update();
        return Erasure.done().retaining("golive", Retention.AUDIT_LOG);
    }
}
