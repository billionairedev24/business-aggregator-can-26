package ca.northline.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: identity's part — the account is blanked to a pseudonym, sign-ins end, addresses keep the place of supply. */
class IdentityPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "identity";
    }

    private String withEverything(Subject person) {
        var u = person.userId();
        var p = Map.of("u", u, "id", Ids.next());
        jdbc.sql("""
                        insert into identity.addresses (id, user_id, label, street, unit, city, province, postal,
                                                        access_note, is_default)
                        values (:id, :u, 'Home', '12 Elm St SW', '4', 'Springfield', 'AB', 'T2R 0K3', 'Buzz 04', true)
                        """).params(p).update();
        jdbc.sql("""
                        insert into identity.sessions (id, user_id, device, ip, city, method)
                        values (:id, :u, 'iPhone', '203.0.113.7'::inet, 'Springfield', 'phone_otp')
                        """).params(Map.of("u", u, "id", Ids.next())).update();
        jdbc.sql("insert into identity.passkeys (id, user_id, device_label) values (:id, :u, 'MacBook')")
                .params(Map.of("u", u, "id", Ids.next()))
                .update();
        jdbc.sql("insert into identity.platform_roles (user_id, role) values (:u, 'staff')")
                .param("u", u)
                .update();
        var household = Ids.next();
        jdbc.sql("insert into identity.households (id, name, plus_plan) values (:h, 'Kowalski home', 'none')")
                .param("h", household)
                .update();
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (:h, :u, 'owner')")
                .param("h", household)
                .param("u", u)
                .update();
        jdbc.sql("""
                        insert into identity.oncall_shifts (id, user_id, starts_at, ends_at, duty, created_by)
                        values (:id, :u, now() + interval '2 days', now() + interval '3 days', 'Payments', :u)
                        """).params(Map.of("u", u, "id", Ids.next())).update();
        return household;
    }

    @Test
    void exportsTheAccountAndEverythingAttachedToIt() {
        var person = person();
        withEverything(person);

        assertThat(records(person, "identity.account")).isEqualTo(1);
        assertThat(section(person, "identity.account").orElseThrow().json()).contains("Kowalski", person.email());
        assertThat(records(person, "identity.addresses")).isEqualTo(1);
        assertThat(records(person, "identity.signIns")).isEqualTo(1);
        assertThat(section(person, "identity.signIns").orElseThrow().json()).contains("203.0.113.7");
        assertThat(records(person, "identity.passkeys")).isEqualTo(1);
        assertThat(records(person, "identity.household")).isEqualTo(1);
        assertThat(records(person, "identity.onCall")).isEqualTo(1);
    }

    @Test
    void erasureBlanksTheAccountEndsSignInsAndKeepsOnlyThePlaceOfSupply() {
        var person = person();
        var household = withEverything(person);
        var p = Map.of("u", person.userId());

        var outcome = erase(person);

        assertThat(contributor().order()).isEqualTo(PersonalDataContributor.LAST);
        assertThat(jdbc.sql("""
                        select concat_ws('|', first_name, last_name, display_name, email, phone, status)
                          from identity.users where id = :u
                        """).params(p).query(String.class).single()).isEqualTo("erased");
        assertThat(text("select erased_at::text from identity.users where id = :u", p))
                .isNotNull();
        assertThat(text(
                        "select concat_ws('|', street, unit, access_note, label, city, province, postal) "
                                + "from identity.addresses where user_id = :u",
                        p))
                .isEqualTo("|Springfield|AB|T2R");
        assertThat(text(
                        "select concat_ws('|', revoke_reason, device, host(ip), city) from identity.sessions "
                                + "where user_id = :u",
                        p))
                .isEqualTo("erased");
        assertThat(count("select count(*) from identity.passkeys where user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from identity.platform_roles where user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from identity.oncall_shifts where user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from identity.household_members where user_id = :u", p))
                .isZero();
        assertThat(text("select name from identity.households where id = :h", Map.of("h", household)))
                .isNull();
        assertThat(outcome.retained()).contains(new Kept<>("identity.addresses.placeOfSupply", Retention.TAX_RECORDS));
        assertThat(outcome.held()).isEmpty();
        assertIdempotent(person, outcome);
    }

    @Test
    void staffCorrectTheVerifiedMobile() {
        var person = person();
        var other = person();

        inTransaction(() -> {
            contributor().correct(person, "phone", "+1 (587) 555-0199");
            return true;
        });

        assertThat(text("select phone from identity.users where id = :u", Map.of("u", person.userId())))
                .isEqualTo("+15875550199");
        assertThat(contributor().correctable()).containsExactly("phone");
        assertThatThrownBy(() -> contributor().correct(person, "phone", "555-0199"))
                .isInstanceOf(RuleViolation.class)
                .hasMessage("Enter a phone number like +1 403 555 0123.");
        assertThatThrownBy(() -> contributor().correct(person, "phone", other.phone()))
                .isInstanceOf(RuleViolation.class)
                .hasMessage("That mobile number is already used by another account.");
    }
}
