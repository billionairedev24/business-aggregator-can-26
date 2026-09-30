package ca.northline.catalogue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * S-30: a partner client reads the listings of the businesses it is bound to (the {@code merchants} claim) with
 * {@code api.read} — and nothing else: other businesses, other scopes, endpoints not marked {@code @PartnerAccess},
 * writes, and endpoints outside a business.
 */
class PartnerListingAccessTest extends IntegrationTest {

    private static final String PARTNER = "partner:acme";

    @Test
    void aBoundPartner_withApiRead_readsTheListings() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/listings", biz.merchantId())
                        .with(TestJwt.partner(PARTNER, "api.read", biz.merchantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void anotherBusiness_is403NotBound() throws Exception {
        var mine = data.business(MerchantRole.OWNER);
        var other = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/listings", other.merchantId())
                        .with(TestJwt.partner(PARTNER, "api.read", mine.merchantId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_bound"));
    }

    @Test
    void withoutTheScope_is403() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/listings", biz.merchantId())
                        .with(TestJwt.partner(PARTNER, "api.write", biz.merchantId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("partner_not_allowed"));
    }

    @Test
    void endpointsNotOpenToPartners_are403_evenForItsBusiness() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var token = TestJwt.partner(PARTNER, "api.read api.write", biz.merchantId());
        mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("partner_not_allowed"));
        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Taken over\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void outsideABusiness_aPartnerTokenReachesNothing() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/me/businesses").with(TestJwt.partner(PARTNER, "api.read", biz.merchantId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void members_areUnchanged() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/merchants/{id}/listings", biz.merchantId()).with(TestJwt.customer(biz.userId())))
                .andExpect(status().isForbidden());
    }
}
