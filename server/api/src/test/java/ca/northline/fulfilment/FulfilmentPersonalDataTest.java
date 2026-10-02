package ca.northline.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: fulfilment's part — a delivered order loses its drop-off details; a courier is made inactive. */
class FulfilmentPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "fulfilment";
    }

    private String delivery(Subject person, String state) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into fulfilment.deliveries (order_id, order_ref, order_type, kind, market, customer_id,
                                                           dropoff, pin, state)
                        values (:id, 'NL-1', 'goods', 'direct', 'mk1', :u,
                                '{"street": "12 Elm St SW", "note": "leave at side door"}', '1234', :state)
                        """)
                .param("id", id)
                .param("u", person.userId())
                .param("state", state)
                .update();
        return id;
    }

    @Test
    void deliveredOrdersLoseTheDropOffAndOnesOnTheWayHold() {
        var person = person();
        var delivered = delivery(person, "delivered");
        var onTheWay = delivery(person, "picked_up");

        assertThat(section(person, "fulfilment.deliveries").orElseThrow().json())
                .contains("side door");

        var outcome = erase(person);

        assertThat(text(
                        "select dropoff::text from fulfilment.deliveries where order_id = :id",
                        Map.of("id", delivered)))
                .isNull();
        assertThat(text(
                        "select dropoff->>'street' from fulfilment.deliveries where order_id = :id",
                        Map.of("id", onTheWay)))
                .isEqualTo("12 Elm St SW");
        assertThat(outcome.held()).containsExactly(new Kept<>("fulfilment.deliveries", Hold.ACTIVE_DELIVERY));
        assertThat(outcome.retained())
                .containsExactly(new Kept<>("fulfilment.deliveries.proofs", Retention.CHARGEBACK_EVIDENCE));
        assertIdempotent(person, outcome);
    }

    @Test
    void aCourierIsMadeInactiveAndTheirRunsStay() {
        var person = person();
        var courier = Ids.next();
        jdbc.sql(
                        "insert into fulfilment.couriers (id, user_id, vehicle, status, market) values (:c, :u, 'car', 'available', 'mk1')")
                .param("c", courier)
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into fulfilment.shifts (id, courier_id, starts_at, ends_at)
                        values (:id, :c, now() + interval '1 day', now() + interval '1 day 4 hours')
                        """).param("id", Ids.next()).param("c", courier).update();

        assertThat(records(person, "fulfilment.courier")).isEqualTo(1);
        assertThat(records(person, "fulfilment.shifts")).isEqualTo(1);

        var outcome = erase(person);

        assertThat(text("select active || '/' || status from fulfilment.couriers where id = :c", Map.of("c", courier)))
                .isEqualTo("false/offline");
        assertThat(text("select state from fulfilment.shifts where courier_id = :c", Map.of("c", courier)))
                .isEqualTo("cancelled");
        assertThat(outcome.retained())
                .containsExactly(new Kept<>("fulfilment.courierRuns", Retention.BUSINESS_RECORDS));
    }
}
