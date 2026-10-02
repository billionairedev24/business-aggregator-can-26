package ca.northline.availability;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: availability's part — a team member's calendars (and what came with them), hours and time off go. */
class AvailabilityPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "availability";
    }

    @Test
    void calendarsHoursAndTimeOffGo() {
        var person = person();
        var u = person.userId();
        var link = Ids.next();
        jdbc.sql("""
                        insert into availability.calendar_links (id, member_user_id, merchant_id, provider, account_label,
                                                                 refresh_token_enc, refresh_token_key)
                        values (:id, :u, 'm1', 'google', 'dana@example.ca', '\\x01'::bytea, '\\x02'::bytea)
                        """).param("id", link).param("u", u).update();
        jdbc.sql(
                        "insert into availability.calendar_sources (link_id, calendar_id, name) values (:l, 'primary', 'Dana')")
                .param("l", link)
                .update();
        jdbc.sql("""
                        insert into availability.calendar_busy_blocks (link_id, calendar_id, external_event_id, starts_at,
                                                                       ends_at)
                        values (:l, 'primary', 'ev1', now(), now() + interval '1 hour')
                        """).param("l", link).update();
        jdbc.sql("""
                        insert into availability.availability_rules (id, merchant_id, member_user_id, weekday, ranges)
                        values (:id, 'm1', :u, 2, '[]')
                        """).param("id", Ids.next()).param("u", u).update();
        jdbc.sql("""
                        insert into availability.time_off (id, merchant_id, member_user_id, starts_on, ends_on, kind,
                                                           reason)
                        values (:id, 'm1', :u, current_date, current_date, 'closed', 'Doctor')
                        """).param("id", Ids.next()).param("u", u).update();

        assertThat(section(person, "availability.calendars").orElseThrow().json())
                .contains("dana@example.ca");
        assertThat(records(person, "availability.hours")).isEqualTo(1);
        assertThat(records(person, "availability.timeOff")).isEqualTo(1);

        var outcome = erase(person);

        var p = Map.of("u", u, "l", link);
        assertThat(count("select count(*) from availability.calendar_links where member_user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from availability.calendar_busy_blocks where link_id = :l", p))
                .isZero();
        assertThat(count("select count(*) from availability.availability_rules where member_user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from availability.time_off where member_user_id = :u", p))
                .isZero();
        assertThat(outcome.retained()).isEmpty();
        assertIdempotent(person, outcome);
    }
}
