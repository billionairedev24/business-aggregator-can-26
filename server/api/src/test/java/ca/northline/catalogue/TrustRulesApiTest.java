package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.messaging.api.OffPlatformPhrases;
import ca.northline.messaging.domain.OutgoingText;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.TestJwt;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-93: trust &amp; safety rules as configuration (tier thresholds, rating floor, keyword lists) feeding the
 * off-platform payment detector and listing vetting; the rating floor's impact; actions on flags (warn emails the
 * owners); the scoped flag queue; audit; role gates.
 */
class TrustRulesApiTest extends CatalogueApiTest {

    static final String RULES = "/api/v1/console/trust/rules";

    @Autowired
    OffPlatformPhrases phrases;

    String staff;

    @BeforeEach
    void staff() {
        staff = data.user("Dev Kaur");
    }

    ResultActions putRule(String key, String value, StaffRole role) throws Exception {
        return mvc.perform(put(RULES + "/{k}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":" + value + "}")
                .with(TestJwt.staff(staff, role)));
    }

    List<String> audit(String targetId) {
        return jdbc.sql(
                        "select action || ':' || role from developer.audit_log where target_id = ? and actor_id = ? order by at")
                .params(targetId, staff)
                .query(String.class)
                .list();
    }

    @Test
    void rulesHaveTheDesignDefaults_andEditsAreValidatedAndLogged() throws Exception {
        mvc.perform(get(RULES).with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key == 'tier_trusted')].defaults.minQuality")
                        .value(80))
                .andExpect(jsonPath("$.items[?(@.key == 'tier_master')].defaults.takeRateBps")
                        .value(900))
                .andExpect(jsonPath("$.items[?(@.key == 'provider_no_shows')].defaults.count")
                        .value(3))
                .andExpect(jsonPath("$.items[?(@.key == 'rating_floor')].fields[0].name")
                        .value("rating"));

        putRule("rating_floor", "{\"rating\":7,\"days\":90,\"recoverDays\":30}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("value.rating"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter a number in the allowed range."));
        putRule("provider_no_shows", "{\"count\":2.5,\"days\":30}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter a whole number in the allowed range."));
        putRule("restricted_keywords", "{\"words\":[]}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add at least one word or phrase."));
        putRule("nope", "{}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick a rule from the list."));

        putRule("customer_no_shows", "{\"count\":3}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.count").value(3))
                .andExpect(jsonPath("$.updatedBy").value(staff));
        assertThat(audit("customer_no_shows")).containsExactly("trust.rule_changed:trust_safety");
    }

    @Test
    void theKeywordLists_feedTheOffPlatformDetector_andListingVetting() throws Exception {
        var phrase = "text me on signal " + Ids.next().substring(20).toLowerCase(Locale.ROOT);
        putRule(
                        "off_platform_phrases",
                        "{\"phrases\":[\"pay me directly\",\"" + phrase.toUpperCase(Locale.ROOT) + "\"]}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.phrases[1]").value(phrase));
        assertThat(phrases.phrases()).contains(phrase);
        assertThat(OutgoingText.of("Sure! " + phrase + " tonight", phrases.phrases())
                        .offPlatform())
                .isTrue();
        assertThat(OutgoingText.of("See you at 2 pm", phrases.phrases()).offPlatform())
                .isFalse();

        var word = "wonder-" + Ids.next().substring(20).toLowerCase(Locale.ROOT);
        putRule("restricted_keywords", "{\"words\":[\"" + word + "\"]}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk());
        var biz = provider(MerchantRole.OWNER);
        verifiedLicence(biz.merchantId(), "AMVIC");
        var id = json(mvc.perform(
                        postJson("/api/v1/merchants/{m}/services", """
                                        {"name":"Brake job","categoryId":"%s","pricingMode":"fixed","priceCents":15000,
                                         "durationMin":60,"bufferMin":15,"included":"Our %s pads","instantBook":true}
                                        """.formatted(MECHANIC, word), biz.merchantId())
                                .with(TestJwt.member(biz.userId()))))
                .get("id")
                .asString();
        mvc.perform(post("/api/v1/merchants/{m}/listings/{id}/submit", biz.merchantId(), id)
                .with(TestJwt.member(biz.userId())));
        await(() -> mvc.perform(get("/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.vettingFlags[0]").value("restricted_keyword")));
    }

    @Test
    void theRatingFloorImpact_countsRatedBusinessesBelowIt() throws Exception {
        var low = data.merchant("provider", "Low Rated Co");
        var high = data.merchant("provider", "High Rated Co");
        for (var m : List.of(low, high)) {
            jdbc.sql("update merchants.merchants set province = 'NU' where id = ?")
                    .params(m)
                    .update();
            for (int i = 0; i < 5; i++) {
                jdbc.sql("""
                                insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating)
                                values (?, 'booking', ?, ?, 'merchant', ?, ?)""")
                        .params(Ids.next(), Ids.next(), Ids.next(), m, m.equals(low) ? 4 : 5)
                        .update();
            }
        }
        mvc.perform(get(RULES + "/rating_floor/impact?rating=4.2&province=NU")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.days").value(90));
        mvc.perform(get(RULES + "/rating_floor/impact?rating=9").with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter a rating from 1 to 5."));
    }

    @Test
    void warningFromAFlag_emailsTheOwners_andActionsIt() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var email = "owner-" + biz.userId().toLowerCase(Locale.ROOT) + "@example.test";
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params(email, biz.userId())
                .update();
        jdbc.sql("update merchants.merchants set province = 'NU' where id = ?")
                .params(biz.merchantId())
                .update();
        var flag = Ids.next();
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                        values (?, 'message', ?, 'off_platform_payment', '{}'::jsonb, 'open', 'system', ?)""").params(flag, Ids.next(), biz.merchantId()).update();

        mvc.perform(get("/api/v1/console/trust/flags/queue?province=NU")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].businessName".formatted(flag))
                        .value("Prairie Wrench provider"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].province".formatted(flag))
                        .value("NU"));

        var action = "/api/v1/console/trust/flags/" + flag + "/action";
        mvc.perform(post(action)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"shout\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose an action."));
        mvc.perform(post(action)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"warn\",\"note\":\"Keep payments on Northline.\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("actioned"))
                .andExpect(jsonPath("$.action").value("warn"));
        assertThat(audit(flag)).containsExactly("trust.flag_decided:trust_safety");
        await(() -> assertThat(emails.to(email)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("A warning from Northline about Prairie Wrench provider");
            assertThat(m.text()).contains("Note from the Northline team: Keep payments on Northline.");
        }));
        mvc.perform(post(action)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"escalate\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("flag_decided"));
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        for (var role : List.of(StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.DISPATCH, StaffRole.ANALYST)) {
            mvc.perform(get(RULES).with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            putRule("customer_no_shows", "{\"count\":4}", role).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/console/trust/flags/" + Ids.next() + "/action")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"warn\"}")
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(RULES).with(TestJwt.staffWithoutMfa(staff, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        assertThat(audit("customer_no_shows")).isEmpty();
    }
}
