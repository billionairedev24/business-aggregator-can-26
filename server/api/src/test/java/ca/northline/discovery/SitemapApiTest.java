package ca.northline.discovery;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.TOBACCO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/public/sitemap[/{section}]} (S-63): only pages a guest can open — published pages of active
 * businesses (with their live custom domain), shop products with a live approved offer, the departments and service
 * categories that have something live; never banned categories, drafts or unpublished pages. Anonymous.
 */
class SitemapApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void categories() {
        shopFixtures.categories();
    }

    private String business(String type, String status) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, structure, tier, status, profile)
                        values (?, ?, 'Sitemap test', 'Sitemap test Ltd.', 'sole', 'trusted', ?, '{}'::jsonb)
                        """).params(id, type, status).update();
        return id;
    }

    private String page(String merchantId, boolean published) {
        var slug = "sm-" + Ids.next().toLowerCase(java.util.Locale.ROOT);
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, published_at,
                               updated_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', case when ? then now() end, now())
                        """).params(Ids.next(), merchantId, slug, published).update();
        return slug;
    }

    private JsonNode read(String path) throws Exception {
        return JSON.readTree(mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** Every item of a section (all pages), key → item. */
    private Map<String, JsonNode> section(String name) throws Exception {
        var out = new HashMap<String, JsonNode>();
        var pages = 0;
        for (var s : read("/api/v1/public/sitemap").path("sections")) {
            if (s.path("name").asString().equals(name)) pages = s.path("pages").asInt();
        }
        for (int p = 1; p <= pages; p++) {
            for (var item :
                    read("/api/v1/public/sitemap/" + name + "?page=" + p).path("items")) {
                out.put(item.path("key").asString(), item);
            }
        }
        return out;
    }

    @Test
    void indexListsEverySectionWithCountsAndPages() throws Exception {
        mvc.perform(get("/api/v1/public/sitemap"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(jsonPath("$.pageSize").value(5000))
                .andExpect(jsonPath("$.sections[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "departments", "kitchens", "products", "providers", "services")));
    }

    @Test
    void businessPagesArePublishedPagesOfActiveBusinesses() throws Exception {
        var provider = business("provider", "active");
        var live = page(provider, true);
        var domain = "book-" + Ids.next().toLowerCase(java.util.Locale.ROOT) + ".example.ca";
        jdbc.sql(
                        "update merchants.storefronts set custom_domain = ?, custom_domain_status = 'live', custom_domain_status_at = now(), custom_domain_token = 'test-token' where slug = ?")
                .params(domain, live)
                .update();
        var both = page(business("both", "active"), true);
        var draft = page(business("provider", "active"), false);
        var suspended = page(business("provider", "suspended"), true);
        var kitchen = page(business("kitchen", "active"), true);

        var providers = section("providers");
        assertThat(providers).containsKeys(live, both).doesNotContainKeys(draft, suspended, kitchen);
        assertThat(providers.get(live).path("customDomain").asString()).isEqualTo(domain);
        assertThat(providers.get(both).path("customDomain").isNull()).isTrue();
        assertThat(providers.get(live).path("updatedAt").asString()).isNotBlank();
        assertThat(section("kitchens")).containsKey(kitchen).doesNotContainKey(live);
    }

    @Test
    void productsAndDepartmentsNeedALiveApprovedOfferAndNoBan() throws Exception {
        var tag = Ids.next().substring(18);
        var shop = shopFixtures.shop("Mapville", "Sitemap shop " + tag, "master");
        var loaf = shopFixtures.listing(shop, BAKERY, "Sitemap loaf " + tag, 650, 5);
        var draft = shopFixtures.listing(shop, BAKERY, "Draft loaf " + tag, 500, 5, "draft", "hidden", "same_day", 0);
        var vape = shopFixtures.listing(shop, TOBACCO, "Vape " + tag, 500, 5);

        assertThat(section("products"))
                .containsKey(loaf.productId())
                .doesNotContainKeys(draft.productId(), vape.productId(), loaf.offerId());
        assertThat(section("departments")).containsKey("bakery").doesNotContainKey("tobacco-and-vape");
    }

    @Test
    void serviceCategoriesWithALiveService() throws Exception {
        var provider = business("provider", "active");
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, pricing_mode,
                               price_cents, duration_min, buffer_min, instant_book, vetting, status)
                        values (?, ?, 'service.automotive.mobile-mechanic', 'Brakes', '{"en":"Brakes"}', 'fixed', 9000,
                                60, 0, true, 'approved', 'live')
                        """).params(Ids.next(), provider).update();
        assertThat(section("services")).containsKey("mobile-mechanic");
    }

    @Test
    void unknownSectionsAre404AndPagesStartAtOne() throws Exception {
        mvc.perform(get("/api/v1/public/sitemap/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/sitemap/products?page=0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a page between 1 and 50000."));
        mvc.perform(get("/api/v1/public/sitemap/products?page=50000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
