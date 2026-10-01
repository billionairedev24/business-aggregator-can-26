package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The DB rules the onboarding and page builder rely on (V016 triggers/checks + V030/V031): each has a failing case.
 * Plain SQL, one statement per transaction, so deferred constraint triggers fire at each commit.
 */
class MerchantTriggersTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String merchant(String type, String structure) {
        var id = data.merchant(type, "Trigger Co");
        jdbc.sql("update merchants.merchants set structure = ?, status = 'applicant' where id = ?")
                .params(structure, id)
                .update();
        return id;
    }

    void category(String merchantId, String categoryId) {
        jdbc.sql(
                        "insert into merchants.merchant_categories (merchant_id, category_id, status) values (?, ?, 'approved')")
                .params(merchantId, categoryId)
                .update();
    }

    void principal(String merchantId, String role, int pct) {
        jdbc.sql(
                        "insert into merchants.merchant_principals (id, merchant_id, legal_name, role, ownership_pct) values (?, ?, 'P', ?, ?)")
                .params(Ids.next(), merchantId, role, pct)
                .update();
    }

    String storefront(String merchantId, String pageKind) {
        var id = Ids.next();
        jdbc.sql(
                        "insert into merchants.storefronts (id, merchant_id, slug, page_kind, cta_label) values (?, ?, ?, ?, 'order_now')")
                .params(id, merchantId, "t-" + id.toLowerCase(Locale.ROOT), pageKind)
                .update();
        return id;
    }

    void section(String storefrontId, String kind, int position, boolean enabled) {
        jdbc.sql(
                        "insert into merchants.storefront_sections (id, storefront_id, kind, position, enabled, settings) values (?, ?, ?, ?, ?, '{}')")
                .params(Ids.next(), storefrontId, kind, position, enabled)
                .update();
    }

    @Test
    void categoryLimit_kitchenHoldsThree() {
        var id = merchant("kitchen", "sole");
        category(id, "food.a");
        category(id, "food.b");
        category(id, "food.c");
        assertThatThrownBy(() -> category(id, "food.d"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("may select at most 3 categories");
    }

    @Test
    void categoryLimit_sellerHoldsFive_rejectedOnesDoNotCount() {
        var id = merchant("seller", "sole");
        for (var c : new String[] {"a", "b", "c", "d", "e"}) {
            category(id, "shop." + c);
        }
        assertThatThrownBy(() -> category(id, "shop.f")).hasMessageContaining("at most 5");
        jdbc.sql(
                        "update merchants.merchant_categories set status = 'rejected' where merchant_id = ? and category_id = 'shop.a'")
                .param(id)
                .update();
        category(id, "shop.f");
    }

    @Test
    void principalsOwnAtMost100Percent() {
        var id = merchant("provider", "corp_ab");
        principal(id, "director", 60);
        assertThatThrownBy(() -> principal(id, "shareholder", 41)).hasMessageContaining("exceeds 100");
        principal(id, "shareholder", 40);
    }

    @Test
    void principalRoleMustFitTheStructure() {
        var partnership = merchant("provider", "partnership");
        principal(partnership, "partner_signing", 50);
        assertThatThrownBy(() -> principal(partnership, "director", 10))
                .hasMessageContaining("not allowed for structure");
        var coop = merchant("seller", "coop");
        principal(coop, "chair", 0);
        assertThatThrownBy(() -> principal(coop, "owner", 0)).hasMessageContaining("not allowed");
    }

    @Test
    void sectionKindsFollowThePageKind() {
        var store = storefront(merchant("seller", "sole"), "store");
        section(store, "catalogue", 0, true);
        assertThatThrownBy(() -> section(store, "menu", 1, true))
                .hasMessageContaining("section kind menu not allowed on store");
        var menuPage = storefront(merchant("kitchen", "sole"), "menu_page");
        assertThatThrownBy(() -> section(menuPage, "services", 0, true))
                .hasMessageContaining("not allowed on menu_page");
    }

    @Test
    void heroAndCtaAreAlwaysOn() {
        var page = storefront(merchant("provider", "sole"), "business_page");
        assertThatThrownBy(() -> section(page, "hero", 0, false)).hasMessageContaining("chk_always_on");
        assertThatThrownBy(() -> section(page, "cta", 1, false)).hasMessageContaining("chk_always_on");
        section(page, "about", 2, false);
    }

    @Test
    void positionsAreUniquePerPage() {
        var page = storefront(merchant("provider", "sole"), "business_page");
        section(page, "hero", 0, true);
        assertThatThrownBy(() -> section(page, "about", 0, true)).hasMessageContaining("ux_section_position");
    }

    @Test
    void storefrontChecks() {
        var id = merchant("provider", "sole");
        assertThatThrownBy(() -> jdbc.sql(
                                "insert into merchants.storefronts (id, merchant_id, slug, page_kind) values (?, ?, 'Bad Slug', 'store')")
                        .params(Ids.next(), id)
                        .update())
                .hasMessageContaining("chk_slug");
        assertThatThrownBy(() -> jdbc.sql(
                                "insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color) values (?, ?, ?, 'store', 'green')")
                        .params(Ids.next(), id, "ok-" + id.toLowerCase(Locale.ROOT))
                        .update())
                .hasMessageContaining("chk_brand_color");
        var first = storefront(id, "store");
        assertThat(first).isNotBlank();
        assertThatThrownBy(() -> storefront(id, "store")).hasMessageContaining("ux_storefront_merchant");
    }

    @Test
    void gstRules() {
        var id = merchant("seller", "corp_ab");
        assertThatThrownBy(() -> jdbc.sql("update merchants.merchants set gst_number = '12345' where id = ?")
                        .param(id)
                        .update())
                .hasMessageContaining("chk_gst_format");
        assertThatThrownBy(() -> jdbc.sql(
                                "update merchants.merchants set status = 'pending', gst_number = null where id = ?")
                        .param(id)
                        .update())
                .hasMessageContaining("chk_gst_required");
        jdbc.sql("update merchants.merchants set status = 'applicant', gst_number = null where id = ?")
                .param(id)
                .update();
    }
}
