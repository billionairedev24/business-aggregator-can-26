package ca.northline.uat.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.uat.application.ScreenshotStorage;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, uat (S-121): the pilot feedback the person sent (their words, the screen, the device line, the screenshot)
 * and their participant row. On erasure the words, device line and screenshots go and the person's id is replaced, so
 * the triage record (state, blocking decision, history) stays for the launch decision without pointing at anyone.
 */
@Component
@RequiredArgsConstructor
class UatPersonalData implements PersonalDataContributor {

    static final String ERASED = "erased";

    private final JdbcClient jdbc;
    private final ScreenshotStorage screenshots;

    @Override
    public String module() {
        return "uat";
    }

    @Override
    public List<Section> export(Subject subject) {
        var who = Map.of("u", subject.userId());
        return List.of(
                section(
                        jdbc,
                        "uat.feedback",
                        "Pilot feedback you sent",
                        "Commentaires envoyés pendant le pilote",
                        """
                                select number, app, category, severity, body, route, app_version, locale, platform,
                                       screenshot_key is not null as screenshot, state, created_at
                                  from uat.feedback where user_id = :u order by created_at
                                """,
                        who),
                section(jdbc, "uat.participants", "Pilot participation", "Participation au pilote", """
                                select p.persona, p.active, p.created_at,
                                       (select json_agg(json_build_object('script', s.script_code, 'outcome', s.outcome,
                                                'comments', s.comments, 'recordedAt', s.recorded_at)
                                                order by s.recorded_at)
                                          from uat.signoffs s where s.participant_id = p.id) as signoffs
                                  from uat.participants p where p.user_id = :u order by p.created_at
                                """, who));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        screenshots.deleteAll("customers/" + u);
        jdbc.sql("delete from uat.screenshots where user_id = :u").param("u", u).update();
        jdbc.sql("""
                        update uat.feedback
                           set user_id = :erased, body = '[erased]', platform = :erased,
                               screenshot_key = null, screenshot_type = null, screenshot_bytes = null
                         where user_id = :u
                        """).param("u", u).param("erased", ERASED).update();
        jdbc.sql("""
                        update uat.signoffs
                           set comments = case when comments is null then null else '[erased]' end
                         where participant_id in (select id from uat.participants where user_id = :u)
                        """).param("u", u).update();
        jdbc.sql("""
                        update uat.participants
                           set user_id = :erased || '-' || id, label = 'Erased participant', active = false
                         where user_id = :u
                        """).param("u", u).param("erased", ERASED).update();
        return Erasure.done();
    }
}
