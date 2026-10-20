package ca.northline.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-152: the console's earning rules (finance screen: finance and admins change them, others read or are refused,
 * every rule has its message, changes are audit-logged), a shop's Plus member price in the Studio (EDIT, every
 * message, 403s), the provider-funded reward's budget rules and "rewards near you".
 */
class LoyaltyConsoleAndStudioApiTest extends IntegrationTest {

    static final String RULES = "/api/v1/console/loyalty/rules";

    @Autowired
    JdbcClient jdbc;

    ResultActions rule(String region, String staff, StaffRole role, String body) throws Exception {
        return mvc.perform(put(RULES + "/{r}", region)
                .with(TestJwt.staff(staff, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void financeSetsAProvincesOwnRule_andRemovesIt() throws Exception {
        var fin = data.user("Fin");
        mvc.perform(get(RULES).with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].regionId").doesNotExist())
                .andExpect(jsonPath("$.items[0].pointsPerDollar").isNumber());
        try {
            rule("prov-nb", fin, StaffRole.FINANCE, """
                            {"pointsPerDollar":4,"kinds":["goods","service"],"neverExpire":true,"referralPoints":500}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionId").value("prov-nb"))
                    .andExpect(jsonPath("$.regionName").value("New Brunswick"))
                    .andExpect(jsonPath("$.pointsPerDollar").value(4))
                    .andExpect(jsonPath("$.expiryMonths").doesNotExist())
                    .andExpect(jsonPath("$.referralPoints").value(500));
            assertThat(jdbc.sql("select count(*) from developer.audit_log where action = 'loyalty.rule_changed'"
                                    + " and actor_id = ? and target_id = 'prov-nb'")
                            .params(fin)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            mvc.perform(delete(RULES + "/prov-nb").with(TestJwt.staff(fin, StaffRole.FINANCE)))
                    .andExpect(status().isNoContent());
            mvc.perform(delete(RULES + "/default").with(TestJwt.staff(fin, StaffRole.FINANCE)))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.sql("delete from region.loyalty_rules where region_id = 'prov-nb'")
                    .update();
        }
    }

    @Test
    void everyRuleHasItsMessage() throws Exception {
        var fin = data.user("Fin");
        rule("prov-nb", fin, StaffRole.FINANCE, """
                        {"pointsPerDollar":101,"kinds":["cars"],"expiryMonths":61,"referralPoints":-1,
                         "referralMonthlyCap":101,"referralMinOrderCents":-5}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'pointsPerDollar')].message")
                        .value("Points per dollar between 0 and 100."))
                .andExpect(jsonPath("$.errors[?(@.field == 'kinds')].message")
                        .value("Choose which sales earn: goods, food or services."))
                .andExpect(jsonPath("$.errors[?(@.field == 'expiryMonths')].message")
                        .value("Points expire after 1 to 60 months, or never."))
                .andExpect(jsonPath("$.errors[?(@.field == 'referralPoints')].message")
                        .value("Referral points between 0 and 100,000."))
                .andExpect(jsonPath("$.errors[?(@.field == 'referralMonthlyCap')].message")
                        .value("Rewarded referrals a month between 0 and 100."))
                .andExpect(jsonPath("$.errors[?(@.field == 'referralMinOrderCents')].message")
                        .value("A first order of $0 or more."));
        rule("mkt-nowhere", fin, StaffRole.FINANCE, "{\"pointsPerDollar\":1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province or market of the region model."));
    }

    @Test
    void othersReadOrAreRefused() throws Exception {
        var support = data.user("Sue");
        mvc.perform(get(RULES).with(TestJwt.staff(support, StaffRole.SUPPORT))).andExpect(status().isForbidden());
        rule("default", data.user("Ana"), StaffRole.ANALYST, "{\"pointsPerDollar\":3}")
                .andExpect(status().isForbidden());
        mvc.perform(put(RULES + "/default")
                        .with(TestJwt.staffWithoutMfa(data.user("Fin"), StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointsPerDollar\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    // ── the Studio ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aShopSetsAPlusMemberPrice_onItsOwnListingOnly() throws Exception {
        shopFixtures.categories();
        var owner = data.user("Shop owner");
        var shop = shopFixtures.shop("Cartville", "Member Shop", "trusted");
        data.member(shop, owner, MerchantRole.OWNER);
        var listing = shopFixtures.listing(shop, ca.northline.support.ShopFixtures.BAKERY, "Rye", 900, 5);
        var path = "/api/v1/merchants/{m}/products/{l}/member-price";
        mvc.perform(get(path, shop, listing.offerId()).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberDiscountPct").doesNotExist());
        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.member(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":60}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("memberDiscountPct"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Choose a member price between 1% and 50% off, or none."));
        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.member(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberDiscountPct").value(10));
        mvc.perform(get("/api/v1/public/plus-prices").param("offers", listing.offerId()))
                .andExpect(jsonPath("$." + listing.offerId()).value(10));

        var other = data.business(MerchantRole.OWNER);
        mvc.perform(get(path, other.merchantId(), listing.offerId()).with(TestJwt.member(other.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.member(other.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":5}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.memberWithoutMfa(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":5}"))
                .andExpect(status().isForbidden());
        var bookkeeper = data.user("Books");
        data.member(shop, bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.member(bookkeeper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":5}"))
                .andExpect(status().isForbidden());

        mvc.perform(put(path, shop, listing.offerId())
                        .with(TestJwt.member(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberDiscountPct\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberDiscountPct").doesNotExist());
    }

    @Test
    void aRewardNeedsABudget_withinItsRange_neverBelowWhatItSpent() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var path = "/api/v1/merchants/{m}/reward";
        var ends = LocalDate.now().plusDays(10);
        mvc.perform(put(path, biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\"}".formatted(ends)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("budgetCents"))
                .andExpect(jsonPath("$.errors[0].message").value("Set a budget for the extra points."));
        mvc.perform(put(path, biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\",\"budgetCents\":500}"
                                .formatted(ends)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a budget between $10 and $10,000."));
        mvc.perform(put(path, biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\",\"budgetCents\":2000}"
                                .formatted(ends)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetCents").value(2000))
                .andExpect(jsonPath("$.spentCents").value(0))
                .andExpect(jsonPath("$.running").value(true));
        jdbc.sql("update trust.merchant_rewards set spent_cents = 1500 where merchant_id = ?")
                .params(biz.merchantId())
                .update();
        mvc.perform(put(path, biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"multiplier\":2,\"budgetCents\":1000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("The budget can't be less than what the reward has already spent."));
    }

    @Test
    void rewardsNearYou_areTheRunningOnesOfActiveBusinessesThere() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        jdbc.sql("update merchants.merchants set city = 'Rewardville' where id = ?")
                .params(biz.merchantId())
                .update();
        mvc.perform(put("/api/v1/merchants/{m}/reward", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":true,"multiplier":3,"label":"gutter cleaning","endsOn":"%s","budgetCents":2000}""".formatted(LocalDate.now().plusDays(7))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/rewards").param("city", "rewardville"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].merchantId").value(biz.merchantId()))
                .andExpect(jsonPath("$.items[0].businessName").value("Prairie Wrench"))
                .andExpect(jsonPath("$.items[0].multiplier").value(3))
                .andExpect(jsonPath("$.items[0].label").value("gutter cleaning"));
        mvc.perform(get("/api/v1/public/rewards").param("city", "Elsewhere"))
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
