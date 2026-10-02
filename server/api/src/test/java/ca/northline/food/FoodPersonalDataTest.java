package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: food's part — a pending POS connection goes; nothing else in food is about a person. */
class FoodPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "food";
    }

    @Test
    void pendingPosConnectionsGo() {
        var person = person();
        jdbc.sql("""
                        insert into food.pos_oauth_requests (state_hash, merchant_id, user_id, provider, created_at,
                                                             expires_at)
                        values (:h, 'm1', :u, 'square', now(), now() + interval '10 minutes')
                        """).param("h", "h-" + Ids.next()).param("u", person.userId()).update();

        assertThat(contributor().export(person)).isEmpty();
        var outcome = erase(person);

        assertThat(count(
                        "select count(*) from food.pos_oauth_requests where user_id = :u",
                        Map.of("u", person.userId())))
                .isZero();
        assertThat(outcome.retained()).isEmpty();
        assertIdempotent(person, outcome);
    }
}
