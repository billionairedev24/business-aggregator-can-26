package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ListingHidden;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-92: the console's listing vetting queue — listings the automated checks flagged, listings with an open S-133 AI
 * flag and dishes held by the S-67 price check; approve / reject with reasons and their side effects (listing state,
 * events, trust flags resolved, audit log, owners' email); "actioned" on a listing's trust flag rejects it; role gates.
 */
class ListingVettingApiTest extends CatalogueApiTest {

    static final String QUEUE = "/api/v1/console/vetting";
    static final String LISTING = "/api/v1/merchants/{m}/listings/{id}";

    String staff;

    @BeforeEach
    void staff() {
        staff = data.user("Dev Kaur");
    }

    String ownerEmail(Business biz) {
        var email = "owner-" + biz.userId().toLowerCase(java.util.Locale.ROOT) + "@example.test";
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params(email, biz.userId())
                .update();
        return email;
    }

    /** A mobile-mechanic service without the AMVIC licence: the automated checks flag it {@code missing_licence}. */
    String flaggedService(Business biz, String name) throws Exception {
        var id = json(mvc.perform(postJson(
                                        "/api/v1/merchants/{m}/services",
                                        """
                                        {"name":"%s","categoryId":"%s","pricingMode":"fixed","priceCents":4900,
                                         "durationMin":60,"bufferMin":15,"included":"Pads and labour","instantBook":true}
                                        """.formatted(name, MECHANIC),
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
        mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.vettingFlags[0]").value("missing_licence")));
        return id;
    }

    ResultActions decide(String path, String body, StaffRole role) throws Exception {
        return mvc.perform(postJson(path, body).with(TestJwt.staff(staff, role)));
    }

    List<Map<String, Object>> audit(String merchantId, String prefix) {
        return jdbc.sql("""
                        select action, actor_id, role, target_type, target_id from developer.audit_log
                         where merchant_id = ? and action like ? order by at""").params(merchantId, prefix + "%").query().listOfRows();
    }

    @Test
    void flaggedListingsAreQueued_andRejectedWithReasons() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var email = ownerEmail(biz);
        var id = flaggedService(biz, "Full brake job");

        mvc.perform(get(QUEUE).with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").isNumber())
                .andExpect(jsonPath("$.autoApproved").isNumber())
                .andExpect(
                        jsonPath("$.items[?(@.id == '%s')].state".formatted(id)).value("pending"))
                .andExpect(
                        jsonPath("$.items[?(@.id == '%s')].kind".formatted(id)).value("service"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].flags[0]".formatted(id))
                        .value("missing_licence"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].regulator".formatted(id))
                        .value("AMVIC"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].businessName".formatted(id))
                        .value("Prairie Wrench provider"));

        var path = QUEUE + "/listings/" + id + "/decision";
        decide(path, "{\"decision\":\"reject\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reasons"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose why the listing is rejected."));
        decide(path, "{\"decision\":\"reject\",\"reasons\":[\"rude\"]}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick reasons from the list."));
        decide(path, "{\"decision\":\"later\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or reject."));
        assertThat(audit(biz.merchantId(), "vetting.")).isEmpty();

        decide(
                        path,
                        "{\"decision\":\"reject\",\"reasons\":[\"licence\",\"pricing\"],\"note\":\"Add your AMVIC licence.\"}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.reasons[0]").value("licence"))
                .andExpect(jsonPath("$.note").value("Add your AMVIC licence."));

        mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("rejected"));
        assertThat(audit(biz.merchantId(), "vetting."))
                .singleElement()
                .satisfies(a -> assertThat(a)
                        .containsEntry("action", "vetting.listing_rejected")
                        .containsEntry("actor_id", staff)
                        .containsEntry("role", "trust_safety")
                        .containsEntry("target_id", id));
        assertThat(jdbc.sql(
                                "select decision, array_to_string(reasons, ',') from catalogue.vetting_decisions where listing_id = ?")
                        .param(id)
                        .query((rs, _) -> rs.getString(1) + ":" + rs.getString(2))
                        .single())
                .isEqualTo("rejected:licence,pricing");
        await(() -> assertThat(emails.to(email)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("“Full brake job” wasn’t approved — Prairie Wrench provider");
            assertThat(m.text())
                    .contains("It needs a licence or permit we don’t have on file.")
                    .contains("Note from the Northline team: Add your AMVIC licence.")
                    .contains("/listings");
        }));
        // decided: it stays listed as rejected and can't be decided again
        mvc.perform(get(QUEUE).with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(
                        jsonPath("$.items[?(@.id == '%s')].state".formatted(id)).value("rejected"));
        decide(path, "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_in_review"));
    }

    @Test
    void approvingAFlaggedListing_publishesIt() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var id = flaggedService(biz, "Mobile oil change");
        decide(
                        QUEUE + "/listings/" + id + "/decision",
                        "{\"decision\":\"approve\",\"note\":\"Licence checked by phone\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"));
        mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("approved"))
                .andExpect(jsonPath("$.status").value("live"));
        assertThat(captured.of(ListingPublished.class, id)).hasSize(1);
        assertThat(audit(biz.merchantId(), "vetting."))
                .extracting(a -> a.get("action"))
                .containsExactly("vetting.listing_approved");
    }

    void aiFlag(String flagId, String listingId, String merchantId) {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                        values (?, 'listing', ?, 'ai_screening',
                                cast('{"source":"ai","explanation":"Guarantee language without terms.","categories":"misleading_claim"}' as jsonb),
                                'open', 'system:trust-screening', ?)
                        """).params(flagId, listingId, merchantId).update();
    }

    String flagState(String flagId) {
        return jdbc.sql("select state from trust.flags where id = ?")
                .param(flagId)
                .query(String.class)
                .single();
    }

    @Test
    void anAiFlagOnALiveListing_isReviewed_andRejectingActionsIt() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        verifiedLicence(biz.merchantId(), "AMVIC");
        var id = json(mvc.perform(postJson("/api/v1/merchants/{m}/services", """
                                        {"name":"Pass inspection, promised","categoryId":"%s","pricingMode":"fixed",
                                         "priceCents":18000,"durationMin":60,"bufferMin":15,"included":"Inspection",
                                         "instantBook":true}
                                        """.formatted(MECHANIC), biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
        mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())));
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.status").value("live")));
        var flag = Ids.next();
        aiFlag(flag, id, biz.merchantId());

        mvc.perform(get(QUEUE).with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(
                        jsonPath("$.items[?(@.id == '%s')].state".formatted(id)).value("pending"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].trustFlags[0].source".formatted(id))
                        .value("ai"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].trustFlags[0].explanation".formatted(id))
                        .value("Guarantee language without terms."));

        decide(
                        QUEUE + "/listings/" + id + "/decision",
                        "{\"decision\":\"reject\",\"reasons\":[\"misleading\"]}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"));
        assertThat(flagState(flag)).isEqualTo("actioned");
        assertThat(captured.of(ListingHidden.class, id)).isNotEmpty();
        mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("rejected"));
    }

    @Test
    void actioningAListingFlagOnTheTrustScreen_rejectsTheListing_dismissingLeavesIt() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        verifiedLicence(biz.merchantId(), "AMVIC");
        var ids = new java.util.ArrayList<String>();
        for (var name : List.of("Brake bleed", "Rotor swap")) {
            var id = json(mvc.perform(postJson(
                                            "/api/v1/merchants/{m}/services",
                                            """
                                            {"name":"%s","categoryId":"%s","pricingMode":"fixed","priceCents":15000,
                                             "durationMin":60,"bufferMin":15,"included":"Labour","instantBook":true}
                                            """.formatted(name, MECHANIC),
                                            biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())));
            await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.status").value("live")));
            ids.add(id);
        }
        var actioned = Ids.next();
        var dismissed = Ids.next();
        aiFlag(actioned, ids.get(0), biz.merchantId());
        aiFlag(dismissed, ids.get(1), biz.merchantId());

        decide(
                        "/api/v1/console/trust/flags/" + actioned + "/decision",
                        "{\"decision\":\"actioned\"}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk());
        decide(
                        "/api/v1/console/trust/flags/" + dismissed + "/decision",
                        "{\"decision\":\"dismissed\"}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk());
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), ids.get(0)).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("rejected")));
        mvc.perform(get(LISTING, biz.merchantId(), ids.get(1)).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("approved"));
        await(() -> assertThat(audit(biz.merchantId(), "vetting."))
                .singleElement()
                .satisfies(a -> assertThat(a)
                        .containsEntry("action", "vetting.listing_rejected")
                        .containsEntry("role", "trust_safety")
                        .containsEntry("target_id", ids.get(0))));
    }

    /** A published dish 70 % above its comparable median, price not confirmed: held by the S-67 price check. */
    String heldDish(String merchantId) {
        var menu = Ids.next();
        jdbc.sql("insert into food.menus (id, merchant_id, name, status) values (?, ?, 'Dinner', 'live')")
                .params(menu, merchantId)
                .update();
        var section = Ids.next();
        jdbc.sql("insert into food.menu_sections (id, menu_id, name, sort) values (?, ?, 'Mains', 0)")
                .params(section, menu)
                .update();
        var item = Ids.next();
        jdbc.sql("""
                        insert into food.menu_items (id, section_id, merchant_id, name, price_cents, allergens, vetting, status,
                                                     price_median_cents)
                        values (?, ?, ?, 'Wagyu pho', 3400, '{}', 'pending', 'published', 2000)
                        """).params(item, section, merchantId).update();
        return item;
    }

    @Test
    void dishesHeldByThePriceCheck_areApprovedOrSentBackToDraft() throws Exception {
        var kitchen = business("kitchen", MerchantRole.OWNER);
        var email = ownerEmail(kitchen);
        var keep = heldDish(kitchen.merchantId());
        var drop = heldDish(kitchen.merchantId());

        mvc.perform(get(QUEUE).with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].kind".formatted(keep))
                        .value("dish"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].flags[0]".formatted(keep))
                        .value("price_check"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].deviationPct".formatted(keep))
                        .value(70))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].medianCents".formatted(keep))
                        .value(2000));

        decide(QUEUE + "/dishes/" + keep + "/decision", "{\"decision\":\"approve\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"));
        assertThat(jdbc.sql("select price_confirmed_cents from food.menu_items where id = ?")
                        .param(keep)
                        .query(Long.class)
                        .single())
                .isEqualTo(3400L);
        decide(
                        QUEUE + "/dishes/" + drop + "/decision",
                        "{\"decision\":\"reject\",\"reasons\":[\"pricing\"]}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"));
        assertThat(jdbc.sql("select status from food.menu_items where id = ?")
                        .param(drop)
                        .query(String.class)
                        .single())
                .isEqualTo("draft");
        decide(QUEUE + "/dishes/" + drop + "/decision", "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict());
        assertThat(audit(kitchen.merchantId(), "vetting."))
                .extracting(a -> a.get("action"))
                .containsExactly("vetting.dish_approved", "vetting.dish_rejected");
        await(() -> assertThat(emails.to(email))
                .singleElement()
                .satisfies(m ->
                        assertThat(m.text()).contains("price of “Wagyu pho”").contains("/kitchen/menu")));
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var id = flaggedService(biz, "Role check");
        var path = QUEUE + "/listings/" + id + "/decision";
        for (var role : List.of(StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.DISPATCH, StaffRole.ANALYST)) {
            mvc.perform(get(QUEUE).with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            decide(path, "{\"decision\":\"approve\"}", role).andExpect(status().isForbidden());
        }
        mvc.perform(get(QUEUE).with(TestJwt.staffWithoutMfa(staff, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(postJson(path, "{\"decision\":\"approve\"}")
                        .header("X-Console-Role", "support")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(get(QUEUE + "?province=ZZ").with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province from the list."));
        assertThat(audit(biz.merchantId(), "vetting.")).isEmpty();
        mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"));
    }
}
