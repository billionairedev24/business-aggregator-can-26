package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.api.FoodOrderRefused;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Age-restricted dishes (2026-10-04): a kitchen can't publish an alcohol dish without its licence, and a pickup with
 * one is handed over only after the counter confirms the photo ID — or refused, which returns the order.
 */
@RecordApplicationEvents
class KitchenAgeCheckApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    KitchenFixtures fx() {
        return new KitchenFixtures(jdbc, data);
    }

    String restrictedPickup(KitchenFixtures fx, KitchenFixtures.Kitchen k, String item) {
        var order = fx.foodOrder(k, data.user("Pat Pickup"), "pickup", item, 900, 2);
        jdbc.sql("update orders.orders set id_check_age = 18 where id = ?")
                .params(order)
                .update();
        jdbc.sql("update orders.order_lines set age_class = 'alcohol' where order_id = ?")
                .params(order)
                .update();
        return order;
    }

    void ready(KitchenFixtures.Kitchen k, String order, String cook) throws Exception {
        var auth = TestJwt.member(cook);
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", order).with(auth))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", order).with(auth))
                .andExpect(status().isOk());
    }

    @Test
    void anAlcoholDishNeedsTheLicenceToBePublished() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var dish = """
                {"menuId":"%s","sectionId":"%s","name":"Saigon lager","priceCents":800,"allergens":["wheat"],
                 "ageClass":"alcohol","publish":%s}""";
        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dish.formatted(menu.menuId(), menu.drinksId(), "true")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("licence_required"));
        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dish.formatted(menu.menuId(), menu.drinksId(), "false")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ageClass").value("alcohol"))
                .andExpect(jsonPath("$.status").value("draft"));
        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dish.replace("alcohol", "beer").formatted(menu.menuId(), menu.drinksId(), "false")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose alcohol, or no age restriction."));
    }

    @Test
    void aRestrictedPickupNeedsTheIdCheckAtTheCounter() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var beer = fx.item(k, menu.drinksId(), "Saigon lager", 900, 0);
        var cook = fx.member(k, MerchantRole.COOK);
        var order = restrictedPickup(fx, k, beer);
        ready(k, order, cook);
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", order).with(TestJwt.member(cook)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Confirm you checked government photo ID, the name matches and the person is of age."));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", order)
                        .with(TestJwt.member(cook))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCheck\":{\"idChecked\":true,\"recipientMatches\":true,\"ofAge\":true}}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select outcome, place, actor_role from restricted.handoff_checks where order_id = ?")
                        .params(order)
                        .query()
                        .singleRow())
                .containsEntry("outcome", "passed")
                .containsEntry("place", "counter")
                .containsEntry("actor_role", "merchant");
    }

    @Test
    void aRefusedPickupIsReturned() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var beer = fx.item(k, menu.drinksId(), "Saigon lager", 900, 0);
        var order = restrictedPickup(fx, k, beer);
        ready(k, order, k.ownerId());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/refuse", order)
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"no_id\"}"))
                .andExpect(status().isOk());
        assertThat(events.stream(FoodOrderRefused.class))
                .anyMatch(e -> e.aggregateId().equals(order));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fx.orderState(order)).isEqualTo("returned"));
        // a delivery is checked by the courier, not at the counter
        var delivery = fx.foodOrder(k, data.user("Del Ivery"), "delivery", beer, 900, 1);
        ready(k, delivery, k.ownerId());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/refuse", delivery)
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"no_id\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_id_check"));
    }
}
