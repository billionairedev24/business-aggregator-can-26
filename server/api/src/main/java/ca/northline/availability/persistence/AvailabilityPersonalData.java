package ca.northline.availability.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, availability: a team member's own hours, time off and connected calendars (Google, Outlook, iCal: the account
 * label, the sealed refresh token, the busy blocks and mirrored events). All of it goes on erasure; the provider's own
 * grant is not revoked at Google or Microsoft (the person can remove it there; runbook).
 */
@Component
@RequiredArgsConstructor
class AvailabilityPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "availability";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "availability.calendars", "Connected calendars", "Calendriers connectés", """
                        select id, merchant_id, provider, account_label, mode, state, connected_at, last_sync_at
                          from availability.calendar_links where member_user_id = :u
                        """, p),
                section(jdbc, "availability.hours", "Your working hours", "Vos heures de travail", """
                        select merchant_id, weekday, ranges, effective_from
                          from availability.availability_rules where member_user_id = :u
                        """, p),
                section(jdbc, "availability.timeOff", "Your time off", "Vos congés", """
                        select merchant_id, starts_on, ends_on, kind, reason
                          from availability.time_off where member_user_id = :u order by starts_on
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        jdbc.sql("""
                        delete from availability.calendar_channels c using availability.calendar_links l
                         where c.link_id = l.id and l.member_user_id = :u
                        """).param("u", u).update();
        // sources, busy blocks and mirrors go with the link (ON DELETE CASCADE)
        jdbc.sql("delete from availability.calendar_links where member_user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from availability.calendar_oauth_requests where member_user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from availability.availability_rules where member_user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from availability.time_off where member_user_id = :u")
                .param("u", u)
                .update();
        return Erasure.done();
    }
}
