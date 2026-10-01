package ca.northline.catalogue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-127: {@code PATCH …/listings/{listingId}/price-stock} — price and stock only (the MCP tool
 * {@code update_listing_price_stock}, partners' inventory sync).
 */
class PriceStockApiTest extends CatalogueApiTest {

    static final String PATH = "/api/v1/merchants/{m}/listings/{id}/price-stock";

    String service(Business biz) throws Exception {
        var body = """
                {"name":"Tire swap","categoryId":"service.automotive.tire-change-and-storage","pricingMode":"fixed",
                 "priceCents":9900,"durationMin":60,"bufferMin":15,"included":"Swap four mounted wheels","instantBook":true}
                """;
        return json(mvc.perform(postJson("/api/v1/merchants/{m}/services", body, biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    static MockHttpServletRequestBuilder patchJson(String body, Object... vars) {
        return patch(PATH, vars).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void anOwnerChangesAServicesPrice() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var id = service(biz);
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void validationMessages() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var id = service(biz);
        mvc.perform(patchJson("{\"priceCents\":0}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("priceCents"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter a price above $0."));
        mvc.perform(patchJson("{\"stock\":-1}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Stock can't be negative."));
        mvc.perform(patchJson("{\"stock\":4}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("stock"))
                .andExpect(jsonPath("$.errors[0].message").value("Services have no stock."));
    }

    @Test
    void aBookkeeperAndAStrangerMayNot() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var id = service(biz);
        var bookkeeper = data.user("Bookkeeper");
        data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id)
                        .with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id)
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void aPartnerNeedsApiWriteForItsBusinesses() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var id = service(biz);
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id)
                        .with(TestJwt.partner("partner:acme", "api.read", biz.merchantId())))
                .andExpect(status().isForbidden());
        mvc.perform(patchJson("{\"priceCents\":10900}", biz.merchantId(), id)
                        .with(TestJwt.partner("partner:acme", "api.write", biz.merchantId())))
                .andExpect(status().isOk());
    }
}
