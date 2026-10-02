package ca.northline.developer.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, developer: the audit log entries of the person's own actions (the most recent 1,000 in the export) and the API
 * keys they created. The audit log is append-only and kept seven years (ids and codes only, V181); API keys belong to
 * the business. Nothing here is erased.
 */
@Component
@RequiredArgsConstructor
class DeveloperPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "developer";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(
                        jdbc,
                        "developer.auditLog",
                        "Actions you took (audit log)",
                        "Vos actions (journal d'audit)",
                        """
                        select at, action, role, target_type, target_id, merchant_id
                          from developer.audit_log where actor_id = :u order by at desc limit 1000
                        """,
                        p),
                section(jdbc, "developer.apiKeys", "API keys you created", "Clés d'API que vous avez créées", """
                        select id, merchant_id, name, created_at, revoked_at
                          from developer.api_keys where created_by = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        return Erasure.done().retaining("developer.auditLog", Retention.AUDIT_LOG);
    }
}
