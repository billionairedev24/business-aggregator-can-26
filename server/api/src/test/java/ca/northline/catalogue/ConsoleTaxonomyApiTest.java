package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.MerchantCategoriesChanged;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.TestJwt;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-94: the console's catalogue taxonomy — categories (create, edit; the seeder leaves edited rows alone), regulators
 * and a category's regulator by province, the category limit per business type (V016's trigger reads it), and
 * businesses' suggested categories merged or approved. Admin only; every change is audited.
 */
class ConsoleTaxonomyApiTest extends CatalogueApiTest {

    static final String BASE = "/api/v1/console/taxonomy";

    String admin;
    String tag;

    @BeforeEach
    void admin() {
        admin = data.user("Priya Natarajan");
        tag = Ids.next().substring(18).toLowerCase(Locale.ROOT);
    }

    ResultActions call(MockHttpServletRequestBuilder request, String body, StaffRole role) throws Exception {
        return mvc.perform(
                request.contentType(MediaType.APPLICATION_JSON).content(body).with(TestJwt.staff(admin, role)));
    }

    List<String> audit(String targetId) {
        return jdbc.sql("select action || ':' || role from developer.audit_log where target_id = ? order by at")
                .params(targetId)
                .query(String.class)
                .list();
    }

    @Test
    void addAndEditCategories_validated_audited_andTheSeederLeavesThemAlone() throws Exception {
        mvc.perform(get(BASE).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.id == '" + MECHANIC + "')].regulatedRegistry")
                        .value("AMVIC"))
                .andExpect(jsonPath("$.limits[0].merchantType").value("provider"));

        call(
                        post(BASE + "/categories"),
                        "{\"root\":\"service\",\"parentId\":\"" + AUTO_PARTS + "\",\"nameEn\":\"\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'parentId')].message")
                        .value("Choose a group of the same root."))
                .andExpect(jsonPath("$.errors[?(@.field == 'nameEn')].message")
                        .value("Enter the English name, 1 to 80 characters."));
        call(post(BASE + "/categories"), "{\"root\":\"garden\",\"nameEn\":\"Lawn\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose services, shop or food."));
        call(
                        post(BASE + "/categories"),
                        "{\"parentId\":\"service.automotive\",\"nameEn\":\"Rust proofing\",\"bookingType\":\"party\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose visit, home, event, appointment or consult."));

        var name = "Rust proofing " + tag;
        var id = "service.automotive.rust-proofing-" + tag;
        call(
                        post(BASE + "/categories"),
                        "{\"parentId\":\"service.automotive\",\"nameEn\":\"" + name + "\",\"nameFr\":\"Antirouille "
                                + tag + "\",\"bookingType\":\"visit\",\"regulatedRegistry\":\"AMVIC\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.root").value("service"))
                .andExpect(jsonPath("$.group").value(false))
                .andExpect(jsonPath("$.sellers").value(0));
        call(
                        post(BASE + "/categories"),
                        "{\"parentId\":\"service.automotive\",\"nameEn\":\"" + name + "\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("category_exists"));
        assertThat(jdbc.sql("select name from catalogue.category_labels where category_id = ? and lang = 'fr'")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("Antirouille " + tag);

        call(
                        put(BASE + "/categories/{id}", MECHANIC),
                        // S-116: the seeded French name (V316) is replaced by staff's own
                        "{\"nameEn\":\"Mobile mechanic\",\"nameFr\":\"Mécanique mobile\",\"bookingType\":\"visit\",\"regulatedRegistry\":\"AMVIC\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameFr").value("Mécanique mobile"));
        new ca.northline.tools.CategorySeeder(dataSource).seed();
        assertThat(jdbc.sql("select name_i18n->>'fr' from catalogue.categories where id = ?")
                        .params(MECHANIC)
                        .query(String.class)
                        .single())
                .isEqualTo("Mécanique mobile");
        call(put(BASE + "/categories/{id}", "service.nope"), "{\"nameEn\":\"Nope\"}", StaffRole.ADMIN)
                .andExpect(status().isNotFound());

        assertThat(audit(id)).containsExactly("catalogue.category_created:admin");
        assertThat(jdbc.sql(
                                "select after::text from developer.audit_log where target_id = ? and action = 'catalogue.category_updated' order by at desc limit 1")
                        .params(MECHANIC)
                        .query(String.class)
                        .single())
                .contains("nameFr")
                .doesNotContain("Mécanique");
    }

    @Test
    void regulatorsByProvince_andACategorysRegulatorInEachProvince() throws Exception {
        var code = "reg-" + tag;
        call(
                        post(BASE + "/regulators"),
                        "{\"code\":\"Bad Code\",\"name\":\"\",\"province\":\"ZZ\",\"website\":\"http://x\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'code')].message")
                        .value("Use 2 to 40 lowercase letters, digits, - or _."))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message")
                        .value("Enter the regulator's name, 1 to 80 characters."))
                .andExpect(jsonPath("$.errors[?(@.field == 'province')].message")
                        .value("Choose a province from the list."))
                .andExpect(jsonPath("$.errors[?(@.field == 'website')].message")
                        .value("The website starts with https:// and is at most 200 characters."));
        call(
                        post(BASE + "/regulators"),
                        "{\"code\":\"" + code
                                + "\",\"name\":\"Test Motor Council\",\"province\":\"NU\",\"website\":\"https://example.org\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.province").value("NU"));
        call(
                        post(BASE + "/regulators"),
                        "{\"code\":\"" + code + "\",\"name\":\"Again\",\"province\":\"NU\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("regulator_exists"));

        var path = BASE + "/categories/{id}/regulators/{p}";
        call(put(path, MECHANIC, "NT"), "{\"regulator\":\"" + code + "\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That regulator is in another province."));
        call(put(path, MECHANIC, "NT"), "{\"regulator\":\"nobody-" + tag + "\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a regulator from the list."));
        call(put(path, MECHANIC, "NU"), "{\"regulator\":\"" + code + "\"}", StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regulators[?(@.province == 'NU')].regulator")
                        .value(code));
        call(put(path, MECHANIC, "NT"), "{\"regulator\":\"none\"}", StaffRole.ADMIN)
                .andExpect(status().isOk());
        assertThat(jdbc.sql(
                                "select count(*) from catalogue.category_regulators where category_id = ? and province = 'NT' and regulator is null")
                        .params(MECHANIC)
                        .query(Long.class)
                        .single())
                .isOne();
        mvc.perform(get(BASE).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.regulators[?(@.code == '" + code + "')].categories")
                        .value(1));

        // categories point at it in NU: it can't move to another province
        call(
                        put(BASE + "/regulators/{c}", code),
                        "{\"name\":\"Test Motor Council\",\"province\":\"NT\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("regulator_in_use"));
        call(put(path, MECHANIC, "NU"), "{\"regulator\":null}", StaffRole.ADMIN).andExpect(status().isOk());
        call(put(path, MECHANIC, "NT"), "{}", StaffRole.ADMIN).andExpect(status().isOk());
        assertThat(jdbc.sql(
                                "select count(*) from catalogue.category_regulators where category_id = ? and province in ('NU','NT')")
                        .params(MECHANIC)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(audit(MECHANIC)).contains("catalogue.category_regulated:admin");
        assertThat(audit(code)).containsExactly("catalogue.regulator_created:admin");
    }

    @Test
    void theCategoryLimitIsEditable_andV016sTriggerEnforcesIt() throws Exception {
        try {
            call(put(BASE + "/limits/{t}", "kitchen"), "{\"max\":0}", StaffRole.ADMIN)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Enter a limit from 1 to 50."));
            call(put(BASE + "/limits/{t}", "garage"), "{\"max\":4}", StaffRole.ADMIN)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose provider, seller, both or kitchen."));
            call(put(BASE + "/limits/{t}", "kitchen"), "{\"max\":4}", StaffRole.ADMIN)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.max").value(4))
                    .andExpect(jsonPath("$.updatedBy").value(admin));

            var kitchen = data.merchant("kitchen", "S94 Kitchen " + tag);
            for (var i = 1; i <= 4; i++) {
                category(kitchen, "food.format.test-" + tag + "-" + i, "approved", null);
            }
            assertThatThrownBy(() -> category(kitchen, "food.format.test-" + tag + "-5", "approved", null))
                    .hasMessageContaining("may select at most 4 categories");
            assertThat(audit("kitchen")).contains("merchants.category_limit_changed:admin");
        } finally {
            jdbc.sql("update merchants.category_limits set max_categories = 3 where merchant_type = 'kitchen'")
                    .update();
        }
    }

    @Test
    void suggestedCategories_mergeIntoAnExistingOne_orBecomeANewOne() throws Exception {
        var suggestion = "suggested:paintless-dent-repair-" + tag;
        var a = data.merchant("provider", "S94 Dent A " + tag);
        var b = data.merchant("provider", "S94 Dent B " + tag);
        jdbc.sql("update merchants.merchants set province = 'NU' where id in (?, ?)")
                .params(a, b)
                .update();
        category(a, suggestion, "requested", "Paintless dent repair " + tag);
        category(b, suggestion, "requested", "paintless dent repair " + tag);
        category(b, MECHANIC, "approved", null);

        mvc.perform(get(BASE).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.suggestions[?(@.id == '" + suggestion + "')].businesses.length()")
                        .value(2));

        call(post(BASE + "/suggestions/{s}/merge", suggestion), "{\"categoryId\":\"\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a category."));
        call(
                        post(BASE + "/suggestions/{s}/merge", suggestion),
                        "{\"categoryId\":\"" + MECHANIC + "\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(1))
                .andExpect(jsonPath("$.alreadyHeld").value(1))
                .andExpect(jsonPath("$.category.id").value(MECHANIC));
        // regulated (AMVIC): the moved business's category waits for its licence check
        assertThat(categoryStatus(a, MECHANIC)).isEqualTo("requested");
        assertThat(jdbc.sql("select count(*) from merchants.merchant_categories where category_id = ?")
                        .params(suggestion)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(captured.events.stream()
                        .filter(e -> e instanceof MerchantCategoriesChanged c
                                && c.aggregateId().equals(a)))
                .hasSize(1);
        assertThat(audit(suggestion)).containsExactly("catalogue.suggestion_merged:admin");
        call(
                        post(BASE + "/suggestions/{s}/merge", suggestion),
                        "{\"categoryId\":\"" + MECHANIC + "\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isNotFound());

        var other = "suggested:mobile-bike-repair-" + tag;
        category(a, other, "requested", "Mobile bike repair " + tag);
        call(
                        post(BASE + "/suggestions/{s}/approve", other),
                        "{\"parentId\":\"service.automotive\",\"bookingType\":\"visit\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value("service.automotive.mobile-bike-repair-" + tag))
                .andExpect(jsonPath("$.category.nameEn").value("Mobile bike repair " + tag))
                .andExpect(jsonPath("$.moved").value(1));
        assertThat(categoryStatus(a, "service.automotive.mobile-bike-repair-" + tag))
                .isEqualTo("approved");
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where merchant_id = ? and action = 'merchant.category_assigned'")
                        .params(a)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void adminOnly_andChangesNeedMfa() throws Exception {
        for (var role : List.of(
                StaffRole.TRUST_SAFETY, StaffRole.FINANCE, StaffRole.SUPPORT, StaffRole.DISPATCH, StaffRole.ANALYST)) {
            mvc.perform(get(BASE).with(TestJwt.staff(admin, role))).andExpect(status().isForbidden());
            call(post(BASE + "/categories"), "{\"parentId\":\"service.automotive\",\"nameEn\":\"X\"}", role)
                    .andExpect(status().isForbidden());
            call(put(BASE + "/limits/{t}", "kitchen"), "{\"max\":3}", role).andExpect(status().isForbidden());
            call(post(BASE + "/regulators"), "{}", role).andExpect(status().isForbidden());
            call(put(BASE + "/regulators/{c}", "x"), "{}", role).andExpect(status().isForbidden());
            call(put(BASE + "/categories/{id}/regulators/{p}", MECHANIC, "NU"), "{}", role)
                    .andExpect(status().isForbidden());
            call(put(BASE + "/categories/{id}", MECHANIC), "{}", role).andExpect(status().isForbidden());
            call(post(BASE + "/suggestions/{s}/approve", "suggested:x"), "{}", role)
                    .andExpect(status().isForbidden());
            call(post(BASE + "/suggestions/{s}/merge", "suggested:x"), "{}", role)
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE).with(TestJwt.staffWithoutMfa(admin, StaffRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(TestJwt.customer(admin))).andExpect(status().isForbidden());
    }

    void category(String merchantId, String categoryId, String status, @Nullable String suggestedName) {
        jdbc.sql("""
                        insert into merchants.merchant_categories (merchant_id, category_id, status, suggested_name)
                        values (?, ?, ?, ?)""").params(merchantId, categoryId, status, suggestedName).update();
    }

    String categoryStatus(String merchantId, String categoryId) {
        return jdbc.sql("select status from merchants.merchant_categories where merchant_id = ? and category_id = ?")
                .params(merchantId, categoryId)
                .query(String.class)
                .single();
    }
}
