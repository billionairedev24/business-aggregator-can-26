package ca.northline.food;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL-level fixtures for the kitchen tests: a kitchen with members, a menu, food orders. No extra Spring context. */
record KitchenFixtures(JdbcClient jdbc, TestData data) {

    record Kitchen(String merchantId, String ownerId) {
        String base() {
            return "/api/v1/merchants/" + merchantId;
        }
    }

    record Menu(String menuId, String mainsId, String drinksId) {}

    /** An approved (active) kitchen with its owner. */
    Kitchen kitchen() {
        var merchantId = data.merchant("kitchen", "Pho Test");
        var owner = data.user("Kitchen Owner");
        data.member(merchantId, owner, MerchantRole.OWNER);
        return new Kitchen(merchantId, owner);
    }

    /** A kitchen still waiting for Northline's approval. */
    Kitchen applicantKitchen() {
        var k = kitchen();
        jdbc.sql("update merchants.merchants set status = 'pending' where id = ?")
                .param(k.merchantId())
                .update();
        return k;
    }

    String member(Kitchen k, MerchantRole role) {
        var user = data.user("Member " + role.code());
        data.member(k.merchantId(), user, role);
        return user;
    }

    Menu menu(Kitchen k, String status) {
        var menuId = Ids.next();
        jdbc.sql("""
                        insert into food.menus (id, merchant_id, name, name_i18n, schedule, status, sort)
                        values (?, ?, 'Dinner menu', '{"en":"Dinner menu"}', '{"mode":"open_hours","days":[]}', ?, 0)
                        """).params(menuId, k.merchantId(), status).update();
        var mains = section(menuId, "Mains", 0);
        var drinks = section(menuId, "Drinks", 1);
        return new Menu(menuId, mains, drinks);
    }

    String section(String menuId, String name, int sort) {
        var id = Ids.next();
        jdbc.sql("insert into food.menu_sections (id, menu_id, name, sort) values (?, ?, ?, ?)")
                .params(id, menuId, name, sort)
                .update();
        return id;
    }

    String item(Kitchen k, String sectionId, String name, long priceCents, int prepAddMin) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into food.menu_items (id, section_id, merchant_id, name, price_cents, allergens, dietary,
                               prep_add_min, available, vetting, status, availability, combo_eligible, sort)
                        values (?, ?, ?, ?, ?, '{}', '{}', ?, true, 'approved', 'published', 'always', true, 0)
                        """)
                .params(id, sectionId, k.merchantId(), name, priceCents, prepAddMin)
                .update();
        return id;
    }

    /** A food order with one line of this kitchen, placed a few minutes ago. */
    String foodOrder(Kitchen k, String customerId, String mode, @Nullable String itemId, long unitCents, int qty) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, fulfilment_mode, placed_at)
                        values (?, ?, ?, 'food', 'placed', ?, ?)
                        """)
                .params(
                        id,
                        "FD-" + id.substring(20),
                        customerId,
                        mode,
                        JdbcTimes.ts(Instant.now().minus(Duration.ofMinutes(3))))
                .update();
        jdbc.sql("""
                        insert into orders.order_lines (id, order_id, merchant_id, menu_item_id, qty, unit_cents, modifiers,
                               title, state)
                        values (?, ?, ?, ?, ?, ?, '[{"name":"Large"}]'::jsonb, 'Pho dac biet', 'pending')
                        """)
                .params(Ids.next(), id, k.merchantId(), itemId, qty, unitCents)
                .update();
        return id;
    }

    String orderState(String orderId) {
        return jdbc.sql("select state from orders.orders where id = ?")
                .param(orderId)
                .query(String.class)
                .single();
    }
}
