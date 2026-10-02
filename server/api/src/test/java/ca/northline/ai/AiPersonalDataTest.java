package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: ai's part — the person's usage records (no prompts or answers are stored) go. */
class AiPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "ai";
    }

    @Test
    void usageRecordsGo() {
        var person = person();
        jdbc.sql("""
                        insert into ai.usage (id, feature, person_id, provider, model, prompt, outcome)
                        values (:id, 'assistant', :u, 'fake', 'fake-model', 'assistant@v1', 'ok')
                        """).param("id", Ids.next()).param("u", person.userId()).update();

        assertThat(records(person, "ai.usage")).isEqualTo(1);
        var outcome = erase(person);
        assertThat(count("select count(*) from ai.usage where person_id = :u", Map.of("u", person.userId())))
                .isZero();
        assertIdempotent(person, outcome);
    }
}
