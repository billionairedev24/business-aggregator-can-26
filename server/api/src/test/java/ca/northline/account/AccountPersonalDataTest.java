package ca.northline.account;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: account's part — favourites and preferences, exported and then deleted. */
class AccountPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "account";
    }

    @Test
    void exportsAndDeletesFavouritesAndPreferences() {
        var person = person();
        var p = Map.of("u", person.userId());
        var merchant = data.merchant("provider", "Sable & Soda");
        jdbc.sql("insert into account.favourites (user_id, merchant_id) values (:u, :m)")
                .param("u", person.userId())
                .param("m", merchant)
                .update();
        jdbc.sql("""
                        insert into account.preferences (user_id, allergies, access_notes, dietary)
                        values (:u, 'peanuts', 'side door', '{halal}')
                        """).params(p).update();

        assertThat(records(person, "account.favourites")).isEqualTo(1);
        assertThat(section(person, "account.preferences").orElseThrow().json()).contains("peanuts", "side door");

        var outcome = erase(person);

        assertThat(count("select count(*) from account.favourites where user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from account.preferences where user_id = :u", p))
                .isZero();
        assertThat(outcome.retained()).isEmpty();
        assertThat(outcome.held()).isEmpty();
        assertIdempotent(person, outcome);
        assertThat(records(person, "account.preferences")).isZero();
    }
}
