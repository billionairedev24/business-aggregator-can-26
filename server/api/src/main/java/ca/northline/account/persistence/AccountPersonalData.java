package ca.northline.account.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** S-105, account: favourites and preferences (diet, allergies, access notes). Nothing is kept after erasure. */
@Component
@RequiredArgsConstructor
class AccountPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "account";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "account.favourites", "Favourites", "Favoris", """
                        select merchant_id, created_at from account.favourites where user_id = :u order by created_at
                        """, p),
                section(jdbc, "account.preferences", "Preferences", "Préférences", """
                        select province, units, time_format, dietary, allergies, accessibility, access_notes, display,
                               updated_at
                          from account.preferences where user_id = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from account.favourites where user_id = :u")
                .param("u", subject.userId())
                .update();
        jdbc.sql("delete from account.preferences where user_id = :u")
                .param("u", subject.userId())
                .update();
        return Erasure.done();
    }
}
