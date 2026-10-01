package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.api.MenuItemAvailabilityChanged;
import ca.northline.food.api.MenuPublished;
import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.MerchantSubmitted;
import ca.northline.shared.Ids;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/** Menu builder: menus, sections, items (onboarding contract), sold out, photos, publish, CSV import, provisioning. */
@RecordApplicationEvents
class MenuApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    List<NavBadgeContributor> badges;

    KitchenFixtures fx() {
        return new KitchenFixtures(jdbc, data);
    }

    static String item(String menuId, String sectionId, String allergens) {
        return """
                {"menuId":"%s","sectionId":"%s","name":"Pho dac biet","description":"16-hour broth","priceCents":1700,
                 "prepAddMin":5,"allergens":%s,"modifierGroupIds":[]}
                """.formatted(menuId, sectionId, allergens);
    }

    @Test
    void onboardingContract_menusAndCreateItem() throws Exception {
        var fx = fx();
        var k = fx.applicantKitchen();
        var menu = fx.menu(k, "draft");

        mvc.perform(get(k.base() + "/menus").with(TestJwt.member(k.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(menu.menuId()))
                .andExpect(jsonPath("$.items[0].name").value("Dinner menu"))
                .andExpect(jsonPath("$.items[0].sections[0].name").value("Mains"))
                .andExpect(jsonPath("$.items[0].sections[1].id").value(menu.drinksId()));
        mvc.perform(get(k.base() + "/modifier-groups").with(TestJwt.member(k.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[\"wheat\",\"soy\",\"wheat\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Pho dac biet"))
                .andExpect(jsonPath("$.priceCents").value(1700))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.allergens", hasSize(2)))
                .andExpect(jsonPath("$.allergens[0]").value("soy"))
                // hidden until approved: no photo yet, kitchen pending
                .andExpect(jsonPath("$.visibility").value("needs_photo"));

        // "None" = declared, empty list
        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.drinksId(), "[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allergens", hasSize(0)));
    }

    @Test
    void itemValidationMessages() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var other = fx.menu(fx.kitchen(), "live");
        var owner = TestJwt.member(k.ownerId());

        mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuId\":\"\",\"sectionId\":\"\",\"name\":\" \",\"allergens\":null}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("Enter a name."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='priceCents')].message").value("Enter a price."))
                .andExpect(jsonPath("$.errors[?(@.field=='allergens')].message")
                        .value("Declare allergens, or choose None."))
                .andExpect(jsonPath("$.errors[?(@.field=='menuId')].message").value("Pick a menu and a section."));

        mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","sectionId":"%s","name":"%s","description":"%s","priceCents":-5,
                                 "allergens":[],"availability":"brunch"}
                                """.formatted(menu.menuId(), menu.mainsId(), "x".repeat(81), "d".repeat(501))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("At most 80 characters."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='description')].message").value("At most 500 characters."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='priceCents')].message").value("Enter a price."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='availability')].message").value("Choose one of the options."));

        // rules that need the kitchen's data: another kitchen's section, unknown allergen, prep option, limit, groups
        mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","sectionId":"%s","name":"Pho","priceCents":1700,"prepAddMin":7,
                                 "allergens":["gluten"],"dietary":["keto"],"dailyLimit":1000,"modifierGroupIds":["%s"]}
                                """.formatted(menu.menuId(), other.mainsId(), Ids.next())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='sectionId')].message").value("Pick a menu and a section."))
                .andExpect(jsonPath("$.errors[?(@.field=='allergens')].message")
                        .value("Pick allergens from the Health Canada list."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='dietary')].message").value("Pick dietary tags from the list."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='prepAddMin')].message").value("Choose one of the options."))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='dailyLimit')].message").value("Enter a limit from 1 to 999."))
                .andExpect(jsonPath("$.errors[?(@.field=='modifierGroupIds')].message")
                        .value("Pick modifier groups from your list."));
    }

    @Test
    void photoMakesAPublishedItemLive_soldOutToggles_andBadgesCountSections() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var owner = TestJwt.member(k.ownerId());
        var created = mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]")))
                .andExpect(jsonPath("$.visibility").value("needs_photo"))
                .andReturn();
        var itemId =
                com.jayway.jsonpath.JsonPath.<String>read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(multipart(k.base() + "/menu-items/{id}/photo", itemId)
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", png(400)))
                        .with(owner))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Use a photo at least 1000 px on the short side."));
        mvc.perform(multipart(k.base() + "/menu-items/{id}/photo", itemId)
                        .file(new MockMultipartFile("file", "a.gif", "image/gif", new byte[] {1, 2}))
                        .with(owner))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Upload a JPEG, PNG or WebP photo under 10 MB."));
        mvc.perform(multipart(k.base() + "/menu-items/{id}/photo", itemId)
                        .file(new MockMultipartFile("file", "pho.png", "image/png", png(1000)))
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPhoto").value(true))
                .andExpect(jsonPath("$.visibility").value("live"));
        assertThat(events.stream(MenuItemAvailabilityChanged.class))
                .anySatisfy(e -> assertThat(e.visible()).isTrue());
        mvc.perform(get(k.base() + "/menu-items/{id}/photo", itemId).with(owner))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("image/png"));
        // S-123: another kitchen can't read the photo through its own path, even knowing the item id
        var stranger = fx.kitchen();
        mvc.perform(get(stranger.base() + "/menu-items/{id}/photo", itemId).with(TestJwt.member(stranger.ownerId())))
                .andExpect(status().isNotFound());

        var cook = fx.member(k, MerchantRole.COOK);
        mvc.perform(post(k.base() + "/menu-items/{id}/sold-out", itemId)
                        .with(TestJwt.member(cook))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"soldOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldOut").value(true));
        assertThat(events.stream(MenuItemAvailabilityChanged.class))
                .anySatisfy(e -> assertThat(e.soldOutOn()).isNotNull());
        mvc.perform(post(k.base() + "/menu-items/{id}/sold-out", itemId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"soldOut\":false}"))
                .andExpect(jsonPath("$.soldOut").value(false));

        var all = new HashMap<String, String>();
        badges.forEach(b -> all.putAll(b.badges(
                new NavBadgeContributor.Context(k.merchantId(), k.ownerId(), MerchantRole.OWNER, Locale.CANADA))));
        assertThat(all).containsEntry("menu", "2 sections");

        // S-43: deleting a live dish tells search to drop it
        mvc.perform(delete(k.base() + "/menu-items/{id}", itemId).with(owner)).andExpect(status().isNoContent());
        assertThat(events.stream(MenuItemAvailabilityChanged.class)).anySatisfy(e -> {
            assertThat(e.aggregateId()).isEqualTo(itemId);
            assertThat(e.visible()).isFalse();
            assertThat(e.soldOutOn()).isNull();
        });
    }

    @Test
    void menusSectionsScheduleAndPublish() throws Exception {
        var fx = fx();
        var k = fx.applicantKitchen();
        var owner = TestJwt.member(k.ownerId());
        var created = mvc.perform(post(k.base() + "/menus")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lunch menu\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn();
        var menuId =
                com.jayway.jsonpath.JsonPath.<String>read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(post(k.base() + "/menus")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter a menu name."));

        var a = sectionId(mvc.perform(post(k.base() + "/menus/{m}/sections", menuId)
                .with(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Noodles\"}")));
        var b = sectionId(mvc.perform(post(k.base() + "/menus/{m}/sections", menuId)
                .with(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Drinks\"}")));
        mvc.perform(post(k.base() + "/menus/{m}/sections", menuId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter a section name."));
        mvc.perform(put(k.base() + "/menus/{m}/sections/order", menuId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionIds\":[\"%s\",\"%s\"]}".formatted(b, a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Drinks"));

        mvc.perform(put(k.base() + "/menus/{m}/schedule", menuId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"window\",\"days\":[2,3,4,5],\"from\":\"11:00\",\"to\":\"14:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.from").value("11:00"));
        mvc.perform(put(k.base() + "/menus/{m}/schedule", menuId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"window\",\"days\":[],\"from\":\"14:00\",\"to\":\"11:00\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='days')].message").value("Pick at least one day."))
                .andExpect(jsonPath("$.errors[?(@.field=='hours[0].to')].message")
                        .value("End time must be after start time."));
        mvc.perform(put(k.base() + "/menus/{m}/schedule", menuId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"quote\",\"noticeHours\":0}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter a notice from 1 to 336 hours."));

        mvc.perform(post(k.base() + "/menus/{m}/publish", menuId).with(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_approved"));
        jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                .param(k.merchantId())
                .update();
        mvc.perform(post(k.base() + "/menus/{m}/publish", menuId).with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("live"));
        assertThat(events.stream(MenuPublished.class))
                .singleElement()
                .satisfies(e -> assertThat(e.aggregateId()).isEqualTo(menuId));
        mvc.perform(post(k.base() + "/menus/{m}/hide", menuId).with(owner))
                .andExpect(jsonPath("$.status").value("hidden"));
    }

    @Test
    void csvImport_allOrNothing() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var owner = TestJwt.member(k.ownerId());
        var bad = "section,name,price,allergens\nMains,Com tam,16.50,fish\nDessert,,abc,\n";
        mvc.perform(multipart(k.base() + "/menus/{m}/import", menu.menuId())
                        .file(new MockMultipartFile(
                                "file", "menu.csv", "text/csv", bad.getBytes(StandardCharsets.UTF_8)))
                        .with(owner))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("rows[3].allergens"));
        mvc.perform(multipart(k.base() + "/menus/{m}/import", menu.menuId())
                        .file(new MockMultipartFile(
                                "file", "menu.csv", "text/csv", "name,price\nx,1\n".getBytes(StandardCharsets.UTF_8)))
                        .with(owner))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("The first row must name the columns: section, name, price, allergens."));
        var good = """
                Section,Name,Price,Allergens,Description
                Mains,Com tam,$16.50,Fish,Broken rice
                Dessert,"Che, three colour",6.5,Milk; Soy,
                Dessert,Mango sticky rice,7,none,
                """;
        mvc.perform(multipart(k.base() + "/menus/{m}/import", menu.menuId())
                        .file(new MockMultipartFile(
                                "file", "menu.csv", "text/csv", good.getBytes(StandardCharsets.UTF_8)))
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsCreated").value(3))
                .andExpect(jsonPath("$.sectionsCreated").value(1));
        mvc.perform(get(k.base() + "/menus/{m}", menu.menuId()).with(owner))
                .andExpect(jsonPath("$.sections[2].name").value("Dessert"))
                .andExpect(jsonPath("$.sections[2].items[0].name").value("Che, three colour"))
                .andExpect(jsonPath("$.sections[2].items[0].allergens", hasSize(2)))
                .andExpect(jsonPath("$.sections[2].items[0].status").value("draft"));
    }

    @Test
    void authorization() throws Exception {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        var itemId = fx.item(k, menu.mainsId(), "Pho", 1700, 0);
        var bookkeeper = fx.member(k, MerchantRole.BOOKKEEPER);
        var cook = fx.member(k, MerchantRole.COOK);

        mvc.perform(get(k.base() + "/menus/{m}", menu.menuId()).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/menu-items")
                        .with(TestJwt.member(bookkeeper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get(k.base() + "/menus").with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get(k.base() + "/menus").with(TestJwt.memberWithoutMfa(k.ownerId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(delete(k.base() + "/menu-items/{id}", itemId).with(TestJwt.member(cook)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(k.base() + "/menu-items/{id}", itemId).with(TestJwt.member(k.ownerId())))
                .andExpect(status().isNoContent());
        var other = fx.kitchen();
        mvc.perform(get(other.base() + "/menus/{m}", menu.menuId()).with(TestJwt.member(other.ownerId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void submittedKitchenGetsAStarterMenu_approvalReauditsItems() {
        var fx = fx();
        var k = fx.applicantKitchen();
        tx.executeWithoutResult(_ -> publisher.publishEvent(
                new MerchantSubmitted(Ids.next(), Instant.now(), k.merchantId(), k.ownerId(), "kitchen")));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from food.menu_sections s join food.menus m on m.id = s.menu_id
                                 where m.merchant_id = ?
                                """)
                                .param(k.merchantId())
                                .query(Integer.class)
                                .single())
                        .isEqualTo(4));

        var section = jdbc.sql("""
                        select s.id from food.menu_sections s join food.menus m on m.id = s.menu_id
                         where m.merchant_id = ? order by s.sort limit 1
                        """).param(k.merchantId()).query(String.class).single();
        var itemId = fx.item(k, section, "Pho", 1700, 0);
        jdbc.sql("update food.menu_items set vetting = 'pending', photo_key = 'x' where id = ?")
                .param(itemId)
                .update();
        jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                .param(k.merchantId())
                .update();
        tx.executeWithoutResult(_ -> publisher.publishEvent(
                new MerchantApproved(Ids.next(), Instant.now(), k.merchantId(), "staff", "kitchen", "registered")));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.sql("select vetting from food.menu_items where id = ?")
                                .param(itemId)
                                .query(String.class)
                                .single())
                        .isEqualTo("approved"));
    }

    // ── S-67: ±40 % price vetting ─────────────────────────────────────────────

    /** Kitchens of one fresh cuisine (no other test's dishes compare). */
    private KitchenFixtures.Kitchen kitchenOf(KitchenFixtures fx, String cuisine) {
        var k = fx.kitchen();
        jdbc.sql("update merchants.merchants set profile = jsonb_build_object('cuisines', jsonb_build_array(?::text)) where id = ?")
                .params(cuisine, k.merchantId())
                .update();
        return k;
    }

    @Test
    void outlierPriceWaitsForTheOwnersConfirmation() throws Exception {
        var fx = fx();
        var cuisine = "s67-" + Ids.next().toLowerCase(Locale.ROOT);
        for (var n = 0; n < 2; n++) { // 6 comparable live dishes, median $15
            var peer = kitchenOf(fx, cuisine);
            var peerMenu = fx.menu(peer, "live");
            for (var price : List.of(1400L, 1500L, 1600L)) {
                fx.item(peer, peerMenu.mainsId(), "Bun", price, 0);
            }
        }
        var k = kitchenOf(fx, cuisine);
        var menu = fx.menu(k, "live");
        var owner = TestJwt.member(k.ownerId());
        var created = mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]").replace("1700", "2300")))
                .andExpect(status().isCreated())
                .andReturn();
        var itemId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString();
        mvc.perform(multipart(k.base() + "/menu-items/{id}/photo", itemId)
                        .file(new MockMultipartFile("file", "pho.png", "image/png", png(1000)))
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("price_check"))
                .andExpect(jsonPath("$.priceCheck.medianCents").value(1500))
                .andExpect(jsonPath("$.priceCheck.deviationPct").value(53))
                .andExpect(jsonPath("$.priceCheck.confirmed").value(false));
        assertThat(vetting(itemId)).isEqualTo("pending");

        // within ±40 %: live, no flag
        mvc.perform(put(k.base() + "/menu-items/{id}", itemId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]").replace("1700", "2000")))
                .andExpect(jsonPath("$.visibility").value("live"))
                .andExpect(jsonPath("$.priceCheck").doesNotExist());

        // far below, then confirmed by the owner: live, and the flag says so
        mvc.perform(put(k.base() + "/menu-items/{id}", itemId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]").replace("1700", "500")))
                .andExpect(jsonPath("$.visibility").value("price_check"))
                .andExpect(jsonPath("$.priceCheck.deviationPct").value(-67));
        mvc.perform(post(k.base() + "/menu-items/{id}/confirm-price", itemId).with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("live"))
                .andExpect(jsonPath("$.priceCheck.confirmed").value(true));
        assertThat(vetting(itemId)).isEqualTo("approved");

        // a new price is checked again
        mvc.perform(put(k.base() + "/menu-items/{id}", itemId)
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]").replace("1700", "450")))
                .andExpect(jsonPath("$.visibility").value("price_check"));
        // cooks may confirm too (EDIT); a bookkeeper may not
        mvc.perform(post(k.base() + "/menu-items/{id}/confirm-price", itemId)
                        .with(TestJwt.member(fx.member(k, MerchantRole.BOOKKEEPER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noPriceCheckWithoutEnoughComparableDishes() throws Exception {
        var fx = fx();
        var cuisine = "s67-" + Ids.next().toLowerCase(Locale.ROOT);
        var peer = kitchenOf(fx, cuisine);
        fx.item(peer, fx.menu(peer, "live").mainsId(), "Bun", 1500, 0); // one dish: too few to compare
        var k = kitchenOf(fx, cuisine);
        var menu = fx.menu(k, "live");
        var owner = TestJwt.member(k.ownerId());
        var created = mvc.perform(post(k.base() + "/menu-items")
                        .with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(menu.menuId(), menu.mainsId(), "[]").replace("1700", "9900")))
                .andReturn();
        var itemId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString();
        mvc.perform(multipart(k.base() + "/menu-items/{id}/photo", itemId)
                        .file(new MockMultipartFile("file", "pho.png", "image/png", png(1000)))
                        .with(owner))
                .andExpect(jsonPath("$.visibility").value("live"));
    }

    private String vetting(String itemId) {
        return jdbc.sql("select vetting from food.menu_items where id = ?")
                .param(itemId)
                .query(String.class)
                .single();
    }

    @Test
    void databaseRejectsAPublishedItemWithoutAllergens() {
        var fx = fx();
        var k = fx.kitchen();
        var menu = fx.menu(k, "live");
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into food.menu_items (id, section_id, merchant_id, name, price_cents, status)
                                values (?, ?, ?, 'Pho', 1700, 'published')
                                """)
                        .params(Ids.next(), menu.mainsId(), k.merchantId())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into food.menu_items (id, section_id, merchant_id, name, price_cents, allergens)
                                values (?, ?, ?, 'Pho', 1700, '{gluten}')
                                """)
                        .params(Ids.next(), menu.mainsId(), k.merchantId())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private String sectionId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(
                result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    static byte[] png(int size) throws Exception {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
