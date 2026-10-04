package ca.northline.restricted;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import org.junit.jupiter.api.Test;

/** S-105 for the restricted module: the age check is exported and deleted; the handoff checks a courier made stay. */
class RestrictedPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "restricted";
    }

    @Test
    void theAgeCheckIsExportedAndErased() {
        var person = person();
        jdbc.sql("""
                        insert into restricted.age_verifications (user_id, state, over_age, verified_on, method, attempts,
                               started_at, updated_at)
                        values (?, 'verified', 21, current_date, 'fake', 1, now(), now())""").params(person.userId()).update();
        jdbc.sql("""
                        insert into restricted.handoff_checks (id, order_id, order_type, required_age, actor_id,
                               actor_role, place, outcome, id_checked, recipient_matches, of_age, checked_at)
                        values (?, ?, 'goods', 19, ?, 'courier', 'door', 'passed', true, true, true, now())""").params(Ids.next(), Ids.next(), person.userId()).update();
        var sections = contributor().export(person);
        assertThat(sections)
                .extracting(s -> s.key())
                .containsExactly("restricted.age_verifications", "restricted.handoff_checks");
        assertThat(sections.getFirst().json()).contains("\"over_age\":21").doesNotContain("session");

        var erasure = erase(person);
        assertThat(jdbc.sql("select count(*) from restricted.age_verifications where user_id = ?")
                        .params(person.userId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(erasure.retained())
                .singleElement()
                .satisfies(k -> assertThat(k.reason()).isEqualTo(Retention.AGE_CHECK_RECORDS));
    }
}
