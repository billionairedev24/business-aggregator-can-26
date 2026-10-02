package ca.northline.orders;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: orders' part — carts and unplaced checkouts go, sales stay; an order on its way holds the erasure. */
class OrdersPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "orders";
    }

    private String order(Subject person, String state) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, ref, subtotal_cents, tax_cents)
                        values (:id, :u, 'food', :state, :ref, 2400, 120)
                        """)
                .param("id", id)
                .param("u", person.userId())
                .param("state", state)
                .param("ref", "FD-" + id.substring(18))
                .update();
        return id;
    }

    private void foodCheckout(Subject person, String id, String state) {
        jdbc.sql("""
                        insert into orders.food_checkouts (id, ref, customer_id, merchant_id, kitchen_name, state,
                               fulfilment_mode, lines, subtotal_cents, total_cents, province, payment_intent, placed_at,
                               delivery)
                        values (:id, :ref, :u, 'm1', 'Pho Dau Bo', :state, 'delivery', '[]', 2400, 2400, 'AB',
                                case when :state = 'placed' then 'pi_test' end,
                                case when :state = 'placed' then now() end,
                                '{"street": "12 Elm St SW", "unit": "4", "city": "Springfield", "postalCode": "T2R 0K3",
                                  "note": "Buzz 04", "lat": 51.03, "lng": -114.07}')
                        """)
                .param("id", id)
                .param("ref", "FC-" + id.substring(16))
                .param("u", person.userId())
                .param("state", state)
                .update();
    }

    @Test
    void finishedOrdersStayAsSalesRecordsWithoutTheStreet() {
        var person = person();
        var p = Map.of("u", person.userId());
        var delivered = order(person, "confirmed");
        foodCheckout(person, delivered, "placed");
        foodCheckout(person, Ids.next(), "abandoned");
        jdbc.sql("insert into orders.carts (id, customer_id) values (:id, :u)")
                .param("id", Ids.next())
                .param("u", person.userId())
                .update();

        assertThat(records(person, "orders.orders")).isEqualTo(1);
        assertThat(records(person, "orders.foodCheckouts")).isEqualTo(2);
        assertThat(records(person, "orders.carts")).isEqualTo(1);

        var outcome = erase(person);

        assertThat(count("select count(*) from orders.orders where customer_id = :u", p))
                .isEqualTo(1);
        assertThat(count("select count(*) from orders.carts where customer_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from orders.food_checkouts where customer_id = :u", p))
                .isEqualTo(1);
        assertThat(text("select delivery::text from orders.food_checkouts where id = :id", Map.of("id", delivered)))
                .isEqualTo("{\"city\": \"Springfield\", \"postalCode\": \"T2R\"}");
        assertThat(outcome.retained()).containsExactly(new Kept<>("orders.orders", Retention.TAX_RECORDS));
        assertThat(outcome.held()).isEmpty();
        assertIdempotent(person, outcome);
    }

    @Test
    void anOrderOnItsWayHoldsTheErasureAndKeepsItsAddress() {
        var person = person();
        var open = order(person, "packing");
        foodCheckout(person, open, "placed");

        var outcome = erase(person);

        assertThat(outcome.held()).containsExactly(new Kept<>("orders.orders", Hold.OPEN_ORDER));
        assertThat(text("select delivery->>'street' from orders.food_checkouts where id = :id", Map.of("id", open)))
                .isEqualTo("12 Elm St SW");
    }
}
