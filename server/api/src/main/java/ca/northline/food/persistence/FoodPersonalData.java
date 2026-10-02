package ca.northline.food.persistence;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, food: who started a POS menu import connection (a ten-minute OAuth state, deleted on erasure) and which cook
 * accepted or handed off a kitchen ticket (ids only — the kitchen's records).
 */
@Component
@RequiredArgsConstructor
class FoodPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "food";
    }

    @Override
    public List<Section> export(Subject subject) {
        return List.of();
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from food.pos_oauth_requests where user_id = :u")
                .param("u", subject.userId())
                .update();
        var tickets =
                jdbc.sql("""
                        select exists (select 1 from food.kitchen_tickets
                                        where accepted_by = :u or handed_off_by = :u)
                        """).param("u", subject.userId()).query(Boolean.class).single();
        return tickets ? Erasure.done().retaining("food.tickets", Retention.BUSINESS_RECORDS) : Erasure.done();
    }
}
