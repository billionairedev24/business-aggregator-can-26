package ca.northline.developer;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S-105: developer's part — the person's own audit entries are exported; the append-only log is kept. */
class DeveloperPersonalDataTest extends PersonalDataTest {

    @Autowired
    AuditTrail audit;

    @Override
    protected String module() {
        return "developer";
    }

    @Test
    void auditEntriesAreExportedAndKept() {
        var person = person();
        inTransaction(() -> {
            audit.record(AuditTrail.Entry.of("m1", person.userId(), "owner", "team.role_changed", "member", "x"));
            return true;
        });

        assertThat(section(person, "developer.auditLog").orElseThrow().json()).contains("team.role_changed");
        var outcome = erase(person);

        assertThat(count("select count(*) from developer.audit_log where actor_id = :u", Map.of("u", person.userId())))
                .isEqualTo(1);
        assertThat(outcome.retained()).containsExactly(new Kept<>("developer.auditLog", Retention.AUDIT_LOG));
    }
}
