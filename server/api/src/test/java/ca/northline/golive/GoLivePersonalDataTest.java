package ca.northline.golive;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105 for golive (S-118): a staff member's records are exported; on erasure their words go, the ids stay. */
class GoLivePersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "golive";
    }

    @Test
    void exportsTheirGoLiveRecords_andErasureBlanksTheirWords() {
        var person = person();
        var u = person.userId();
        var record = Ids.next();
        var request = Ids.next();
        jdbc.sql("""
                        insert into golive.gate_records (id, market_id, gate, status, evidence, evidence_url, recorded_by, recorded_at)
                        values (:id, 'mkt-x', 'pentest', 'pass', 'Report from Dana, 403-555-0148', 'https://example.test/r', :u, now())
                        """).param("id", record).param("u", u).update();
        jdbc.sql("""
                        insert into golive.launch_requests (id, market_id, requested_by, requested_at, expires_at, note, override,
                                                            override_reason)
                        values (:id, 'mkt-x', :u, now(), now() + interval '1 day', 'Call me first', true,
                                'Launching for the long weekend, board decision.')
                        """).param("id", request).param("u", u).update();

        assertThat(section(person, "golive.gate_records").orElseThrow().json()).contains("403-555-0148");
        assertThat(records(person, "golive.launch_requests")).isEqualTo(1);

        var outcome = erase(person);

        assertThat(text(
                        "select evidence || coalesce(evidence_url, '') from golive.gate_records where id = :id",
                        Map.of("id", record)))
                .isEqualTo("[erased]");
        assertThat(text(
                        "select concat_ws('|', note, override_reason, requested_by) from golive.launch_requests where id = :id",
                        Map.of("id", request)))
                .isEqualTo("[erased: override reason]|" + u);
        assertThat(outcome.retained()).contains(new Kept<>("golive", Retention.AUDIT_LOG));
        assertIdempotent(person, outcome);
    }
}
