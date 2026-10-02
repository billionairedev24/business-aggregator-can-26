package ca.northline.region.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** S-105, region: the waitlists the person joined (by account or by email). They go on erasure. */
@Component
@RequiredArgsConstructor
class RegionPersonalData implements PersonalDataContributor {

    private static final String MINE = """
            from region.waitlist w
             where w.user_id = :u or (cast(:email as text) is not null and lower(w.email) = lower(:email))
            """;

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "region";
    }

    @Override
    public List<Section> export(Subject subject) {
        return List.of(section(
                jdbc,
                "region.waitlist",
                "Waitlists",
                "Listes d'attente",
                "select w.region_id, w.email, w.locale, w.created_at " + MINE,
                params(subject)));
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from region.waitlist where id in (select w.id " + MINE + ")")
                .params(params(subject))
                .update();
        return Erasure.done();
    }

    private static java.util.Map<String, @Nullable Object> params(Subject subject) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("u", subject.userId());
        p.put("email", subject.email());
        return p;
    }
}
