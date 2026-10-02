package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * S-116 (Loi 96 readiness): French listing text where the region configuration asks for it. The rule is region data —
 * V315 makes a province whose first language is French require it — so the test puts the business in such a province
 * and in one without the rule; nothing in the code names either.
 */
class ListingFrenchApiTest extends CatalogueApiTest {

    static final String SERVICES = "/api/v1/merchants/{m}/services";
    static final String FRENCH = "/api/v1/merchants/{m}/listings/{id}/french";
    static final String SERVICE = """
            {"name":"Brake inspection","categoryId":"%s","pricingMode":"fixed","priceCents":8900,"durationMin":60,
             "bufferMin":0,"included":"Pads, rotors, calipers","instantBook":true}
            """.formatted(MECHANIC);

    @Autowired
    ServiceOffers offers;

    /** The province of the region rows whose first language is French (V117 data), read, not named. */
    String frenchFirstProvince() {
        return jdbc.sql(
                        "select province from region.regions where kind = 'province' and french_first order by sort limit 1")
                .query(String.class)
                .single();
    }

    String inProvince(Business biz, String province) {
        jdbc.sql("update merchants.merchants set province = ? where id = ?")
                .params(province, biz.merchantId())
                .update();
        return province;
    }

    String newService(Business biz) throws Exception {
        return json(mvc.perform(postJson(SERVICES, SERVICE, biz.merchantId()).with(TestJwt.member(biz.userId()))))
                .get("id")
                .asString();
    }

    @Test
    void frenchFirstPlace_requiresTheFrenchTextBeforeSubmitting() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        inProvince(biz, frenchFirstProvince());
        var id = newService(biz);

        mvc.perform(get(FRENCH, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("require"))
                .andExpect(jsonPath("$.missing").value(true))
                .andExpect(jsonPath("$.title").value(""));
        mvc.perform(get("/api/v1/merchants/{m}", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.region.frenchFirst").value(true))
                .andExpect(jsonPath("$.region.frenchListings").value("require"));

        mvc.perform(post("/api/v1/merchants/{m}/listings/{id}/submit", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("french"))
                .andExpect(jsonPath("$.errors[0].rule").value("required"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(org.hamcrest.Matchers.startsWith(
                                "Add the French name and description first: listings in ")));
        mvc.perform(post("/api/v1/merchants/{m}/listings/{id}/submit", biz.merchantId(), id)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value(org.hamcrest.Matchers.startsWith(
                                "Ajoutez d’abord le nom et la description en français : les annonces ")));

        // the name is required, the description follows the listing's own limit
        mvc.perform(putJson(FRENCH, "{\"title\":\"  \",\"description\":\"x\"}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter the French name."));
        mvc.perform(putJson(FRENCH, "{\"title\":\"Inspection des freins\"}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(true)); // still no description
        mvc.perform(putJson(
                                FRENCH,
                                "{\"title\":\"Inspection des freins\",\"description\":\"Plaquettes, disques, étriers\"}",
                                biz.merchantId(),
                                id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(false))
                .andExpect(jsonPath("$.description").value("Plaquettes, disques, étriers"));

        mvc.perform(post("/api/v1/merchants/{m}/listings/{id}/submit", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());

        // customers reading French see the merchant's French text once the service is live
        jdbc.sql("update catalogue.services set vetting = 'approved', status = 'live' where id = ?")
                .params(id)
                .update();
        var french = offers.find(id, "fr").orElseThrow();
        assertThat(french.name()).isEqualTo("Inspection des freins");
        assertThat(french.included()).isEqualTo("Plaquettes, disques, étriers");
        assertThat(offers.find(id, "en").orElseThrow().name()).isEqualTo("Brake inspection");

        // deleting the listing deletes its French text (and keeps this live service out of other tests' comparables)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from catalogue.listing_texts where listing_id = ?")
                        .params(id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void placeWithoutTheRule_submitsWithoutFrench_andShowsNoWarning() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var province = jdbc.sql("select province from region.regions where kind = 'province' and not french_first"
                        + " and french_listings = 'off' order by sort limit 1")
                .query(String.class)
                .single();
        inProvince(biz, province);
        var id = newService(biz);

        mvc.perform(get(FRENCH, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.rule").value("off"));
        mvc.perform(post("/api/v1/merchants/{m}/listings/{id}/submit", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isNoContent()); // out of other tests' price comparables
    }

    @Test
    void onlyEditorsWriteIt_andOnlyTheBusinessReadsIt() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var id = newService(biz);
        var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
        var technician = member(biz.merchantId(), MerchantRole.TECHNICIAN);
        var body = "{\"title\":\"Inspection des freins\"}";

        mvc.perform(putJson(FRENCH, body, biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(get(FRENCH, biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(putJson(FRENCH, body, biz.merchantId(), id).with(TestJwt.member(technician)))
                .andExpect(status().isOk());
        mvc.perform(get(FRENCH, biz.merchantId(), id).with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        var other = provider(MerchantRole.OWNER);
        mvc.perform(get(FRENCH, other.merchantId(), id).with(TestJwt.member(other.userId())))
                .andExpect(status().isNotFound());
    }

    /** The region endpoint carries the rules (the web, Studio and apps decide French-first from it). */
    @Test
    void regionsSayWhichPlacesAreFrenchFirst() throws Exception {
        var first = frenchFirstProvince();
        mvc.perform(get("/api/v1/geo/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provinces[?(@.code=='%s')].frenchFirst".formatted(first))
                        .value(true))
                .andExpect(jsonPath("$.provinces[?(@.code=='%s')].frenchListings".formatted(first))
                        .value("require"))
                .andExpect(jsonPath("$.provinces[?(@.frenchFirst==false)]").isNotEmpty());
    }
}
