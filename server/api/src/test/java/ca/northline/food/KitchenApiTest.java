package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.food.api.KitchenAutoPaused;
import ca.northline.food.api.KitchenAutoResumed;
import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import ca.northline.food.api.KitchenPaused;
import ca.northline.food.api.KitchenResumed;
import ca.northline.shared.Ids;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Live orders (KDS), Hours / prep / capacity, modifier groups, combos and promos. */
@RecordApplicationEvents
class KitchenApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    List<NavBadgeContributor> badges;

    KitchenFixtures fx() {
        return new KitchenFixtures(jdbc, data);
    }

    // ── Live orders ─────────────────────────────────────────────────────────────

    @Test
    void acceptReadyHandOff_publishesEventsAndMovesTheOrder() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var pho = fx.item(k, menu.mainsId(), "Pho dac biet", 1700, 10);
        var customer = data.user("Amara Osei");
        var order = fx.foodOrder(k, customer, "delivery", pho, 13000, 1);
        var pickup = fx.foodOrder(k, customer, "pickup", pho, 1700, 1);
        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));

        mvc.perform(get(k.base() + "/kitchen/live").with(cook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].stage").value("new"))
                .andExpect(jsonPath("$.items[0].customerName").value("A. Osei"))
                .andExpect(jsonPath("$.items[0].lines[0].modifiers[0]").value("Large"))
                .andExpect(jsonPath("$.items[0].handoff.state").value("finding"))
                .andExpect(jsonPath("$.counts.fresh").value(2))
                .andExpect(jsonPath("$.prep.shownMin").value(25));

        var all = new HashMap<String, String>();
        badges.forEach(b -> all.putAll(b.badges(
                new NavBadgeContributor.Context(k.merchantId(), k.ownerId(), MerchantRole.OWNER, Locale.CANADA))));
        assertThat(all).containsEntry("kds", "2 cooking");
        var fr = new HashMap<String, String>();
        badges.forEach(b -> fr.putAll(b.badges(new NavBadgeContributor.Context(
                k.merchantId(), k.ownerId(), MerchantRole.OWNER, Locale.CANADA_FRENCH))));
        assertThat(fr).containsEntry("kds", "2 en cuisine");

        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", order).with(cook))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("kitchen_stage"));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", order).with(cook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.cooking").value(1));
        // 25 default + 10 slowest item + 15 large order ($130 ≥ $120)
        assertThat(events.stream(KitchenOrderAccepted.class)).singleElement().satisfies(e -> {
            assertThat(e.prepMin()).isEqualTo(50);
            assertThat(e.aggregateId()).isEqualTo(order);
        });
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", order).with(cook))
                .andExpect(status().isConflict());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", order).with(cook))
                .andExpect(status().isOk());
        assertThat(events.stream(KitchenOrderReady.class))
                .singleElement()
                .satisfies(e -> assertThat(e.late()).isFalse());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", order).with(cook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
        assertThat(events.stream(FoodOrderHandedOff.class))
                .singleElement()
                .satisfies(e -> assertThat(e.fulfilmentMode()).isEqualTo("delivery"));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fx.orderState(order)).isEqualTo("picked_up"));

        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", pickup).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", pickup).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", pickup).with(cook))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fx.orderState(pickup)).isEqualTo("delivered"));
    }

    @Test
    void cancelledOrdersCantMove_otherKitchensOrdersAre404() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var order = fx.foodOrder(k, data.user("C"), "delivery", fx.item(k, menu.mainsId(), "Pho", 1700, 0), 1700, 1);
        jdbc.sql("update orders.orders set state = 'cancelled' where id = ?")
                .param(order)
                .update();
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", order).with(TestJwt.member(k.ownerId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_closed"));
        var other = fx.kitchen();
        mvc.perform(post(other.base() + "/kitchen/live/{o}/accept", order).with(TestJwt.member(other.ownerId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void busyBumpAndPause() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var owner = TestJwt.member(k.ownerId());
        for (int i = 1; i <= 6; i++) {
            mvc.perform(post(k.base() + "/kitchen/prep-bump").with(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.prep.bumpMin").value(i * 5));
        }
        mvc.perform(post(k.base() + "/kitchen/prep-bump").with(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("prep_bump_max"));
        mvc.perform(delete(k.base() + "/kitchen/prep-bump").with(owner))
                .andExpect(jsonPath("$.prep.shownMin").value(25));

        mvc.perform(post(k.base() + "/kitchen/pause").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausedUntil").isNotEmpty());
        assertThat(events.stream(KitchenPaused.class)).hasSize(1);
        mvc.perform(delete(k.base() + "/kitchen/pause").with(owner))
                .andExpect(jsonPath("$.pausedUntil").doesNotExist());
        assertThat(events.stream(KitchenResumed.class)).hasSize(1);

        var bookkeeper = fx.member(k, MerchantRole.BOOKKEEPER);
        mvc.perform(get(k.base() + "/kitchen/live").with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/pause").with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(get(k.base() + "/kitchen/live").with(TestJwt.member(data.user("X"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(k.base() + "/kitchen/live").with(TestJwt.memberWithoutMfa(k.ownerId())))
                .andExpect(status().isForbidden());
    }

    // ── S-67: auto-pause on late orders ─────────────────────────────────────────

    @Autowired
    ca.northline.food.application.KitchenUseCases.KitchenAutoPause autoPause;

    @Test
    void lateOrdersAutoPauseTheKitchen_andCatchingUpResumesIt() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var owner = TestJwt.member(k.ownerId());
        mvc.perform(
                        put(k.base() + "/kitchen/prep")
                                .with(owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"defaultPrepMin\":25,\"maxOrdersPer15\":8,\"largeOrderCents\":20000,\"autoPauseLate\":3}"))
                .andExpect(status().isOk());
        var menu = fx.menu(k, "live");
        var dish = fx.item(k, menu.mainsId(), "Pho", 1700, 0);
        var orders = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var order = fx.foodOrder(k, data.user("C" + i), "pickup", dish, 1700, 1);
            orders.add(order);
            jdbc.sql("""
                            insert into food.kitchen_tickets (order_id, merchant_id, stage, accepted_at, ready_by)
                            values (?, ?, 'cooking', now() - interval '40 minutes', now() - interval '10 minutes')
                            """).params(order, k.merchantId()).update();
        }

        autoPause.check(k.merchantId());
        assertThat(events.stream(KitchenAutoPaused.class)).singleElement().satisfies(e -> {
            assertThat(e.aggregateId()).isEqualTo(k.merchantId());
            assertThat(e.lateOrders()).isEqualTo(3);
            assertThat(e.threshold()).isEqualTo(3);
        });
        autoPause.check(k.merchantId()); // no change, no second event
        assertThat(events.stream(KitchenAutoPaused.class)).hasSize(1);
        mvc.perform(get(k.base() + "/kitchen/live").with(owner))
                .andExpect(jsonPath("$.autoPause.lateOrders").value(3))
                .andExpect(jsonPath("$.autoPause.threshold").value(3))
                .andExpect(jsonPath("$.autoPause.active").value(true));
        assertThat(jdbc.sql("select auto_paused_at is not null from food.kitchen_settings where merchant_id = ?")
                        .param(k.merchantId())
                        .query(Boolean.class)
                        .single())
                .isTrue();

        // one order ready: 2 late < 3 → resumed at once
        mvc.perform(post(k.base() + "/kitchen/live/{id}/ready", orders.getFirst())
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoPause.active").value(false));
        assertThat(events.stream(KitchenAutoResumed.class))
                .singleElement()
                .satisfies(e -> assertThat(e.aggregateId()).isEqualTo(k.merchantId()));
    }

    @Test
    void autoPauseOffNeverPauses() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        mvc.perform(
                        put(k.base() + "/kitchen/prep")
                                .with(TestJwt.member(k.ownerId()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"defaultPrepMin\":25,\"maxOrdersPer15\":8,\"largeOrderCents\":20000,\"autoPauseLate\":null}"))
                .andExpect(status().isOk());
        var menu = fx.menu(k, "live");
        var dish = fx.item(k, menu.mainsId(), "Pho", 1700, 0);
        for (int i = 0; i < 6; i++) {
            var order = fx.foodOrder(k, data.user("C" + i), "pickup", dish, 1700, 1);
            jdbc.sql("""
                            insert into food.kitchen_tickets (order_id, merchant_id, stage, accepted_at, ready_by)
                            values (?, ?, 'cooking', now() - interval '40 minutes', now() - interval '10 minutes')
                            """).params(order, k.merchantId()).update();
        }
        autoPause.check(k.merchantId());
        assertThat(events.stream(KitchenAutoPaused.class)).isEmpty();
        mvc.perform(get(k.base() + "/kitchen/live").with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.autoPause.lateOrders").value(6))
                .andExpect(jsonPath("$.autoPause.active").value(false));
    }

    // ── Hours, prep & capacity ──────────────────────────────────────────────────

    @Test
    void setupDefaults_andEdits() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var owner = TestJwt.member(k.ownerId());
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_key, check_type, registry, status, reference)
                        values (?, ?, 'ahs_permit', 'ahs_permit', 'AHS', 'verified', 'FS-2024-88120')
                        """).params(Ids.next(), k.merchantId()).update();
        mvc.perform(get(k.base() + "/kitchen/setup").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prep.defaultPrepMin").value(25))
                .andExpect(jsonPath("$.fulfilment.pickupFromMin").value(15))
                .andExpect(jsonPath("$.hours", hasSize(7)))
                .andExpect(jsonPath("$.foodSafety.permit.reference").value("FS-2024-88120"))
                .andExpect(jsonPath("$.foodSafety.handlers.status").doesNotExist());

        mvc.perform(
                        put(k.base() + "/kitchen/prep")
                                .with(owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"defaultPrepMin\":30,\"maxOrdersPer15\":8,\"largeOrderCents\":20000,\"autoPauseLate\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prep.largeOrderAddMin").value(20))
                .andExpect(jsonPath("$.prep.autoPauseLate").doesNotExist());
        mvc.perform(
                        put(k.base() + "/kitchen/prep")
                                .with(owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"defaultPrepMin\":22,\"maxOrdersPer15\":5,\"largeOrderCents\":1,\"autoPauseLate\":4}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of the options."));

        mvc.perform(put(k.base() + "/kitchen/fulfilment")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courier":true,"pickup":false,"mealKits":true,"scheduled":true,"scheduledDays":3,
                                 "groupOrders":true,"groupMax":8,"radiusKm":4.5,"areas":["Beltline"," Beltline "]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfilment.pickup").value(false))
                .andExpect(jsonPath("$.fulfilment.mealKits").value(true))
                .andExpect(jsonPath("$.fulfilment.areas", hasSize(1)));
        mvc.perform(put(k.base() + "/kitchen/fulfilment")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courier":true,"pickup":true,"mealKits":false,"scheduled":true,"scheduledDays":30,
                                 "groupOrders":true,"groupMax":1,"radiusKm":40}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[?(@.field=='radiusKm')].message").value("Enter a radius from 1 to 25 km."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='groupMax')].message").value("Enter a group size from 2 to 50."))
                .andExpect(jsonPath("$.errors[?(@.field=='scheduledDays')].message")
                        .value("Enter 1 to 14 days."));

        mvc.perform(put(k.base() + "/kitchen/hours")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"days":[{"weekday":1,"ranges":[]},{"weekday":2,"ranges":[["11:00","14:00"],["17:00","21:00"]],
                                  "note":"lunch special 11–2"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours[1].ranges", hasSize(2)))
                .andExpect(jsonPath("$.hours[1].note").value("lunch special 11–2"));
        mvc.perform(put(k.base() + "/kitchen/hours")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"days":[{"weekday":3,"ranges":[["21:00","11:00"]]},
                                         {"weekday":4,"ranges":[["11:00","15:00"],["14:00","20:00"]]},
                                         {"weekday":5,"ranges":[["9am","5pm"]]}]}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='days[0].ranges[0].to')].message")
                        .value("End time must be after start time."))
                .andExpect(jsonPath("$.errors[?(@.field=='days[1].ranges[1].from')].message")
                        .value("These hours overlap another range on the same day."))
                .andExpect(jsonPath("$.errors[?(@.field=='days[2].ranges[0].from')].message")
                        .value("Use a time like 11:00."));

        var tomorrow = LocalDate.now(ZoneId.of("America/Edmonton")).plusDays(1);
        mvc.perform(post(k.base() + "/kitchen/holiday-hours")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"%s\",\"ranges\":[],\"note\":\"Closed for family\"}".formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidays", hasSize(1)));
        mvc.perform(post(k.base() + "/kitchen/holiday-hours")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2020-01-01\",\"ranges\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick today or a later date."));
    }

    @Test
    void databaseChecksOnSettings() {
        var k = fx().kitchen();
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15)
                                values (?, 21, 6)
                                """).param(k.merchantId()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbc.sql("""
                                insert into food.kitchen_tickets (order_id, merchant_id, stage) values (?, ?, 'cooking')
                                """).params(Ids.next(), k.merchantId()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── Modifier groups, combos, promos ─────────────────────────────────────────

    @Test
    void modifierGroups() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var owner = TestJwt.member(k.ownerId());
        var size = mvc.perform(post(k.base() + "/modifier-groups")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Size","pickRule":"exactly","pickCount":1,"required":true,
                                 "options":[{"name":"Regular","isDefault":true},{"name":"Large","priceDeltaCents":300}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minSelect").value(1))
                .andExpect(jsonPath("$.maxSelect").value(1))
                .andExpect(jsonPath("$.options[1].priceDeltaCents").value(300))
                .andReturn();
        var sizeJson = size.getResponse().getContentAsString();
        String sizeId = com.jayway.jsonpath.JsonPath.read(sizeJson, "$.id");
        String largeId = com.jayway.jsonpath.JsonPath.read(sizeJson, "$.options[1].id");

        var noodles = mvc.perform(post(k.base() + "/modifier-groups")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Noodles","pickRule":"up_to","pickCount":2,"showForOptionIds":["%s"],
                                 "options":[{"name":"Extra noodles","priceDeltaCents":200}]}
                                """.formatted(largeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.showForOptionIds[0]").value(largeId))
                .andExpect(jsonPath("$.maxSelect").value(2))
                .andReturn();
        String noodlesId =
                com.jayway.jsonpath.JsonPath.read(noodles.getResponse().getContentAsString(), "$.id");

        mvc.perform(post(k.base() + "/modifier-groups/{g}/options", sizeId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Family\",\"priceDeltaCents\":800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options", hasSize(3)));

        mvc.perform(post(k.base() + "/modifier-groups")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"pickRule\":\"exactly\",\"pickCount\":0,\"options\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("Enter a group name."))
                .andExpect(jsonPath("$.errors[?(@.field=='pickCount')].message").value("Enter a number from 1 to 20."))
                .andExpect(jsonPath("$.errors[?(@.field=='options')].message").value("Add at least one option."));
        mvc.perform(post(k.base() + "/modifier-groups")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Spice","pickRule":"exactly","pickCount":2,"showForOptionIds":["%s"],
                                 "options":[{"name":"","priceDeltaCents":-1}]}
                                """.formatted(Ids.next())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='options[0].name')].message")
                        .value("Enter an option name."))
                .andExpect(jsonPath("$.errors[?(@.field=='options[0].priceDeltaCents')].message")
                        .value("Enter a price change from $0 to $100."));
        mvc.perform(post(k.base() + "/modifier-groups")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Spice","pickRule":"exactly","pickCount":2,"showForOptionIds":["%s"],
                                 "options":[{"name":"Mild"}]}
                                """.formatted(Ids.next())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='pickCount')].message")
                        .value("There aren't enough options to pick that many."))
                .andExpect(jsonPath("$.errors[?(@.field=='showForOptionIds')].message")
                        .value("Pick options from another group."));

        // deleting Size drops the nesting on Noodles
        mvc.perform(delete(k.base() + "/modifier-groups/{g}", sizeId).with(owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(k.base() + "/modifier-groups").with(owner))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(noodlesId))
                .andExpect(jsonPath("$.items[0].showForOptionIds", hasSize(0)));
    }

    @Test
    void combosAndPromos() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        fx.item(k, menu.mainsId(), "Pho", 1700, 0);
        fx.item(k, menu.mainsId(), "Vegan pho", 1600, 0);
        var coffee = fx.item(k, menu.drinksId(), "Iced coffee", 550, 0);
        var owner = TestJwt.member(k.ownerId());

        mvc.perform(post(k.base() + "/combos")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Pho for two","pricing":"fixed","priceCents":3900,"status":"live",
                                 "slots":[{"label":"Any 2 mains","qty":2,"sectionId":"%s"},
                                          {"label":"2 iced coffees","qty":2,"itemIds":["%s"]}]}
                                """.formatted(menu.mainsId(), coffee)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rule").value("Any 2 mains + 2 iced coffees"))
                .andExpect(jsonPath("$.referenceCents").value(4300))
                .andExpect(jsonPath("$.savingCents").value(400));
        mvc.perform(post(k.base() + "/combos")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lunch deal","pricing":"percent_off","discountPct":10,"status":"scheduled",
                                 "schedule":{"days":[2,3],"from":"11:00","to":"14:00"},
                                 "slots":[{"label":"1 main","qty":1,"sectionId":"%s"}]}
                                """.formatted(menu.mainsId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents").value(1440))
                .andExpect(jsonPath("$.savingCents").value(160));

        mvc.perform(post(k.base() + "/combos")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"pricing\":\"fixed\",\"status\":\"live\",\"slots\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("Enter a combo name."))
                .andExpect(jsonPath("$.errors[?(@.field=='slots')].message").value("Add at least one slot."));
        mvc.perform(post(k.base() + "/combos")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","pricing":"percent_off","discountPct":95,"status":"live",
                                 "schedule":{"days":[],"from":"14:00","to":"11:00"},
                                 "slots":[{"label":"","qty":0},{"label":"x","qty":1,"itemIds":["%s"]}]}
                                """.formatted(Ids.next())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='slots[0].label')].message")
                        .value("Describe this slot, e.g. Any 2 mains."))
                .andExpect(jsonPath("$.errors[?(@.field=='slots[0].qty')].message")
                        .value("Enter a quantity from 1 to 20."));
        mvc.perform(post(k.base() + "/combos")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","pricing":"percent_off","discountPct":95,"status":"live",
                                 "schedule":{"days":[],"from":"14:00","to":"11:00"},
                                 "slots":[{"label":"x","qty":1,"itemIds":["%s"]}]}
                                """.formatted(Ids.next())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='slots[0].items')].message")
                        .value("Pick a section or items for this slot."))
                .andExpect(jsonPath("$.errors[?(@.field=='discountPct')].message")
                        .value("Enter a discount from 1 to 90 %."))
                .andExpect(jsonPath("$.errors[?(@.field=='schedule.days')].message")
                        .value("Pick at least one day."));

        mvc.perform(get(k.base() + "/combos").with(owner)).andExpect(jsonPath("$.items", hasSize(2)));

        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(put(k.base() + "/kitchen/promos/first_order_5")
                        .with(cook)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(k.base() + "/kitchen/promos/first_order_5")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get(k.base() + "/kitchen/promos").with(cook))
                .andExpect(jsonPath("$.items[1].promo").value("first_order_5"))
                .andExpect(jsonPath("$.items[1].enabled").value(true));
        mvc.perform(put(k.base() + "/kitchen/promos/free_lunch")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }
}
