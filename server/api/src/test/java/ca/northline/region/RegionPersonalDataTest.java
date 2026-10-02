package ca.northline.region;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: region's part — waitlist entries, by account and by the email the person gave. */
class RegionPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "region";
    }

    @Test
    void waitlistEntriesByAccountAndByEmailGo() {
        var person = person();
        var region = jdbc.sql("select id from region.regions where kind = 'province' order by sort limit 1")
                .query(String.class)
                .single();
        jdbc.sql("""
                        insert into region.waitlist (id, region_id, user_id, email) values
                          (:a, :r, :u, null), (:b, :r, null, upper(:email))
                        """)
                .param("a", Ids.next())
                .param("b", Ids.next())
                .param("r", region)
                .param("u", person.userId())
                .param("email", person.email())
                .update();

        assertThat(records(person, "region.waitlist")).isEqualTo(2);

        var outcome = erase(person);

        assertThat(count(
                        "select count(*) from region.waitlist where user_id = :u or lower(email) = :e",
                        Map.of("u", person.userId(), "e", person.email())))
                .isZero();
        assertThat(outcome.retained()).isEmpty();
        assertIdempotent(person, outcome);
    }
}
