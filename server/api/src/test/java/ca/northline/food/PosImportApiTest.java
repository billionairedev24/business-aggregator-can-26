package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.KitchenFixtures.Kitchen;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-36 through the api with the local fakes ({@code northline.pos.provider=local}): connect (Square / Clover OAuth
 * through the shared callback, Toast by restaurant GUID), preview with its diff, apply, re-import diff (changed,
 * unchanged, removed → hidden), allergens left for the kitchen to confirm, and the authorization rules.
 */
class PosImportApiTest extends IntegrationTest {

    static final String TOAST_GUID = "2b8a3c4d-1111-4e2f-9a0b-123456789abc";

    @Autowired
    JdbcClient jdbc;

    KitchenFixtures fx() {
        return new KitchenFixtures(jdbc, data);
    }

    ResultActions postJson(String path, String body, String user) throws Exception {
        return mvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(TestJwt.member(user)));
    }

    /** Connect Square (the fake's consent page is the shared callback) and return the preview id after importing. */
    void connect(Kitchen k, String pos, String menuId) throws Exception {
        String url = JsonPath.read(
                postJson(k.base() + "/pos/" + pos + "/connect", "{\"menuId\":\"" + menuId + "\"}", k.ownerId())
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.authorizationUrl");
        assertThat(url).startsWith("http://localhost:8080/api/v1/commerce/oauth/" + pos + "/callback?code=fake-");
        var uri = URI.create(url);
        mvc.perform(get(uri.getPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(
                                "Location",
                                "http://localhost:3100/b/" + k.merchantId() + "/kitchen/menu?pos=" + pos
                                        + "&result=connected&menu=" + menuId));
    }

    String preview(Kitchen k, String menuId, String pos) throws Exception {
        return JsonPath.read(
                postJson(k.base() + "/menus/" + menuId + "/pos-imports", "{\"provider\":\"" + pos + "\"}", k.ownerId())
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    String itemId(String menuId, String name) {
        return jdbc.sql("""
                        select i.id from food.menu_items i join food.menu_sections s on s.id = i.section_id
                        where s.menu_id = ? and i.name = ?""").params(menuId, name).query(String.class).single();
    }

    @Test
    void squareImportCreatesDraftsWhoseAllergensTheKitchenConfirms() throws Exception {
        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        mvc.perform(get(k.base() + "/pos/connections").with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].provider").value("square"))
                .andExpect(jsonPath("$.items[0].kind").value("oauth"))
                .andExpect(jsonPath("$.items[2].kind").value("restaurant_id"))
                .andExpect(jsonPath("$.items[0].state").value("disconnected"));
        connect(k, "square", menu.menuId());
        mvc.perform(get(k.base() + "/pos/connections").with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.items[0].state").value("connected"))
                .andExpect(jsonPath("$.items[0].accountLabel").value("Pho Dau Bo (Square, local fake)"));
        assertThat(jdbc.sql("select credentials_enc is not null from food.pos_connections where merchant_id = ?")
                        .params(k.merchantId())
                        .query(Boolean.class)
                        .single())
                .isTrue();

        var id = preview(k, menu.menuId(), "square");
        mvc.perform(get(k.base() + "/pos-imports/" + id).with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.status").value("preview"))
                .andExpect(jsonPath("$.diff.counts.newItems").value(7))
                .andExpect(jsonPath("$.diff.counts.problems").value(1))
                .andExpect(jsonPath("$.diff.counts.newSections").value(3)) // "Drinks" exists: matched
                .andExpect(jsonPath("$.diff.counts.newGroups").value(3))
                .andExpect(jsonPath("$.diff.items[?(@.name == 'Soup of the day')].problem")
                        .value(hasItem("No price in your POS — set one there, or add this item by hand.")))
                .andExpect(jsonPath("$.diff.groups[?(@.name == 'Size')].rule").value(hasItem("exactly")))
                .andExpect(jsonPath("$.diff.groups[?(@.name == 'Extras')].rule").value(hasItem("up_to")));
        // nothing is written by a preview
        assertThat(jdbc.sql("select count(*) from food.menu_items where merchant_id = ?")
                        .params(k.merchantId())
                        .query(Integer.class)
                        .single())
                .isZero();

        postJson(k.base() + "/pos-imports/" + id + "/apply", "{}", k.ownerId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsCreated").value(7))
                .andExpect(jsonPath("$.sectionsCreated").value(3))
                .andExpect(jsonPath("$.groupsCreated").value(3))
                .andExpect(jsonPath("$.skipped").value(1));
        postJson(k.base() + "/pos-imports/" + id + "/apply", "{}", k.ownerId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_closed"));

        var pho = itemId(menu.menuId(), "Pho tai");
        var row = jdbc.sql(
                        "select status, allergens is null as undeclared, price_cents, section_id from food.menu_items where id = ?")
                .params(pho)
                .query((rs, _) -> List.of(rs.getString(1), rs.getBoolean(2), rs.getLong(3), rs.getString(4)))
                .single();
        assertThat(row.subList(0, 3)).containsExactly("draft", true, 1695L);
        assertThat(jdbc.sql("select name from food.menu_sections where id = ?")
                        .params(row.get(3))
                        .query(String.class)
                        .single())
                .isEqualTo("Pho");
        assertThat(jdbc.sql("""
                        select g.name || ':' || g.pick_rule || ':' || g.pick_count || ':' || coalesce(g.required, false)
                        from food.item_modifiers im join food.modifier_groups g on g.id = im.group_id
                        where im.item_id = ? order by im.sort""").params(pho).query(String.class).list())
                .containsExactly("Size:exactly:1:true", "Extras:up_to:3:false");
        // "Drinks" was reused, not duplicated
        assertThat(jdbc.sql("select count(*) from food.menu_sections where menu_id = ? and name = 'Drinks'")
                        .params(menu.menuId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        // the builder shows them as drafts; the item editor asks for allergens before anything goes live
        mvc.perform(get(k.base() + "/menus/" + menu.menuId()).with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.sections[?(@.name == 'Pho')].items[0].allergens")
                        .value(hasItem((Object) null)))
                .andExpect(jsonPath("$.sections[?(@.name == 'Pho')].items[0].visibility")
                        .value(hasItem("draft")));
    }

    @Test
    void reImportShowsAndAppliesOnlyWhatThePosChanged() throws Exception {
        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        connect(k, "square", menu.menuId());
        postJson(k.base() + "/pos-imports/" + preview(k, menu.menuId(), "square") + "/apply", "{}", k.ownerId())
                .andExpect(status().isOk());

        // the kitchen declared allergens and renamed a dish in Northline; the POS didn't change those → kept
        var rolls = itemId(menu.menuId(), "Salad rolls (2)");
        jdbc.sql("update food.menu_items set allergens = '{shellfish,peanuts}', name = 'Fresh rolls' where id = ?")
                .params(rolls)
                .update();
        // the POS changed the tofu banh mi since the last import (simulated: its import record is stale)
        var tofu = itemId(menu.menuId(), "Tofu banh mi");
        jdbc.sql("update food.menu_items set price_cents = 999 where id = ?")
                .params(tofu)
                .update();
        jdbc.sql("update food.pos_links set content_hash = 'stale' where local_id = ?")
                .params(tofu)
                .update();
        // a dish imported earlier that the POS no longer has, live on Northline
        var gone = fx().item(k, menu.mainsId(), "Bun bo Hue", 1795, 0);
        jdbc.sql("""
                        insert into food.pos_links (merchant_id, provider, kind, scope, external_id, local_id, content_hash, updated_at)
                        values (?, 'square', 'item', ?, 'item-bun-bo-hue', ?, 'x', now())""").params(k.merchantId(), menu.menuId(), gone).update();

        var id = preview(k, menu.menuId(), "square");
        mvc.perform(get(k.base() + "/pos-imports/" + id).with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.diff.counts.newItems").value(0))
                .andExpect(jsonPath("$.diff.counts.changedItems").value(1))
                .andExpect(jsonPath("$.diff.counts.unchangedItems").value(6))
                .andExpect(jsonPath("$.diff.counts.removedItems").value(1))
                .andExpect(jsonPath("$.diff.counts.newGroups").value(0))
                .andExpect(jsonPath("$.diff.items[?(@.name == 'Tofu banh mi')].fields[0]")
                        .value(hasItem("price")))
                .andExpect(jsonPath("$.diff.items[?(@.name == 'Tofu banh mi')].previousPriceCents")
                        .value(hasItem(999)))
                .andExpect(jsonPath("$.diff.items[?(@.name == 'Bun bo Hue')].change")
                        .value(hasItem("removed")));
        postJson(k.base() + "/pos-imports/" + id + "/apply", "{}", k.ownerId())
                .andExpect(jsonPath("$.itemsCreated").value(0))
                .andExpect(jsonPath("$.itemsUpdated").value(1))
                .andExpect(jsonPath("$.itemsHidden").value(1));

        assertThat(jdbc.sql("select price_cents from food.menu_items where id = ?")
                        .params(tofu)
                        .query(Long.class)
                        .single())
                .isEqualTo(1195L);
        assertThat(jdbc.sql("select name || ':' || array_to_string(allergens, ',') from food.menu_items where id = ?")
                        .params(rolls)
                        .query(String.class)
                        .single())
                .isEqualTo("Fresh rolls:shellfish,peanuts");
        // removed from the POS → hidden (draft), never deleted
        assertThat(jdbc.sql("select status from food.menu_items where id = ?")
                        .params(gone)
                        .query(String.class)
                        .single())
                .isEqualTo("draft");
    }

    @Test
    void toastLinksByRestaurantGuidAndCloverByOAuth() throws Exception {
        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        postJson(k.base() + "/pos/toast/connect", "{\"restaurantId\":\"not-a-guid\"}", k.ownerId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("restaurantId"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Enter your Toast restaurant GUID (Toast Web › Integrations)."));
        postJson(
                        k.base() + "/pos/toast/connect",
                        "{\"restaurantId\":\"00000000-0000-0000-0000-000000000000\"}",
                        k.ownerId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[0].message")
                                .value(
                                        "Northline can't read this restaurant yet. Turn on the Northline integration in Toast, then try again."));
        postJson(k.base() + "/pos/toast/connect", "{\"restaurantId\":\"" + TOAST_GUID + "\"}", k.ownerId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").doesNotExist())
                .andExpect(jsonPath("$.connection.state").value("connected"))
                .andExpect(jsonPath("$.connection.accountLabel").value("Pho Dau Bo (Toast, local fake)"));
        var id = preview(k, menu.menuId(), "toast");
        mvc.perform(get(k.base() + "/pos-imports/" + id).with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.provider").value("toast"))
                .andExpect(jsonPath("$.diff.counts.newItems").value(7));

        connect(k, "clover", menu.menuId());
        mvc.perform(get(k.base() + "/pos/connections").with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.items[1].provider").value("clover"))
                .andExpect(jsonPath("$.items[1].accountLabel").value("Pho Dau Bo (Clover, local fake)"));
        postJson(k.base() + "/pos/clover/disconnect", "{}", k.ownerId())
                .andExpect(jsonPath("$.state").value("disconnected"));
        postJson(k.base() + "/menus/" + menu.menuId() + "/pos-imports", "{\"provider\":\"clover\"}", k.ownerId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_connected"));
    }

    @Test
    void previewsExpireCanBeDiscardedAndTheStateIsSingleUse() throws Exception {
        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        connect(k, "square", menu.menuId());
        var discard = preview(k, menu.menuId(), "square");
        postJson(k.base() + "/pos-imports/" + discard + "/discard", "{}", k.ownerId())
                .andExpect(jsonPath("$.status").value("discarded"));
        postJson(k.base() + "/pos-imports/" + discard + "/apply", "{}", k.ownerId())
                .andExpect(status().isConflict());

        var old = preview(k, menu.menuId(), "square");
        jdbc.sql("update food.pos_imports set created_at = now() - interval '2 hours' where id = ?")
                .params(old)
                .update();
        postJson(k.base() + "/pos-imports/" + old + "/apply", "{}", k.ownerId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("preview_expired"));

        // a consent whose owner lost the right to manage the kitchen meanwhile
        String url = JsonPath.read(
                postJson(k.base() + "/pos/square/connect", "{}", k.ownerId())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.authorizationUrl");
        jdbc.sql("update merchants.merchant_members set role = 'cook' where merchant_id = ? and user_id = ?")
                .params(k.merchantId(), k.ownerId())
                .update();
        var uri = URI.create(url);
        mvc.perform(get(uri.getPath() + "?" + uri.getRawQuery()))
                .andExpect(header().string(
                                "Location",
                                "http://localhost:3100/b/" + k.merchantId()
                                        + "/kitchen/menu?pos=square&result=failed"));
        // used once already
        mvc.perform(get(uri.getPath() + "?" + uri.getRawQuery()))
                .andExpect(header().string("Location", "http://localhost:3100/?commerce=square&result=expired"));
        var denied = UriComponentsBuilder.fromUriString(url)
                .replaceQueryParam("code")
                .queryParam("error", "access_denied");
        assertThat(denied.build().toUriString()).contains("error=access_denied");
    }

    @Nested
    class Authorization {

        @Test
        void cooksImportButOnlyTheOwnerConnects() throws Exception {
            var k = fx().kitchen();
            var menu = fx().menu(k, "draft");
            var cook = fx().member(k, MerchantRole.COOK);
            postJson(k.base() + "/pos/square/connect", "{}", cook)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            connect(k, "square", menu.menuId());
            var id = JsonPath.read(
                    postJson(k.base() + "/menus/" + menu.menuId() + "/pos-imports", "{\"provider\":\"square\"}", cook)
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.id");
            postJson(k.base() + "/pos-imports/" + id + "/apply", "{}", cook).andExpect(status().isOk());
        }

        @Test
        void bookkeepersOutsidersAndSingleFactorAreRefused() throws Exception {
            var k = fx().kitchen();
            var menu = fx().menu(k, "draft");
            var bookkeeper = fx().member(k, MerchantRole.BOOKKEEPER);
            postJson(k.base() + "/menus/" + menu.menuId() + "/pos-imports", "{\"provider\":\"square\"}", bookkeeper)
                    .andExpect(status().isForbidden());
            var other = fx().kitchen();
            mvc.perform(get(k.base() + "/pos/connections").with(TestJwt.member(other.ownerId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(post(k.base() + "/pos/square/connect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(TestJwt.memberWithoutMfa(k.ownerId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void validationAndUnknownThings() throws Exception {
            var k = fx().kitchen();
            var menu = fx().menu(k, "draft");
            postJson(k.base() + "/menus/" + menu.menuId() + "/pos-imports", "{}", k.ownerId())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("provider"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose your POS."));
            postJson(k.base() + "/pos/lightspeed/connect", "{}", k.ownerId()).andExpect(status().isNotFound());
            postJson(k.base() + "/menus/" + Ids.next() + "/pos-imports", "{\"provider\":\"square\"}", k.ownerId())
                    .andExpect(status().isNotFound());
        }
    }
}
