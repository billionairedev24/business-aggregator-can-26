package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.awt.Color;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Real photos (2026-10-19): a service's photos of the work — uploaded through {@code POST /media} like a product's
 * images, saved with the service editor ({@code imageIds}), shown in the listings table, public once approved.
 */
class ServicePhotosApiTest extends CatalogueApiTest {

    static final String SERVICES = "/api/v1/merchants/{m}/services";

    @Autowired
    ServiceOffers offers;

    String upload(Business biz, Color colour) throws Exception {
        return json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                                .file(new MockMultipartFile("file", "work.png", "image/png", png(1200, 900, colour)))
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    static String service(String imageIds) {
        return """
                {"name":"Brake inspection","categoryId":"%s","pricingMode":"fixed","priceCents":8900,"durationMin":60,
                 "included":"Pads and rotors","instantBook":true,"imageIds":%s}
                """.formatted(MECHANIC, imageIds);
    }

    @Test
    void photosAreSaved_shownInTheEditorAndTheTable_andPublicOnceApproved() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var main = upload(biz, new Color(120, 90, 60));
        var second = upload(biz, new Color(60, 90, 120));
        var id = json(mvc.perform(
                                postJson(SERVICES, service("[\"%s\",\"%s\"]".formatted(main, second)), biz.merchantId())
                                        .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.images[0].id").value(main))
                        .andExpect(jsonPath("$.images[1].id").value(second))
                        .andExpect(jsonPath("$.images[0].url")
                                .value("/api/v1/merchants/%s/media/%s".formatted(biz.merchantId(), main))))
                .get("id")
                .asString();

        mvc.perform(get("/api/v1/merchants/{m}/listings?kind=service", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items[0].imageUrl")
                        .value("/api/v1/merchants/%s/media/%s".formatted(biz.merchantId(), main)));
        // unvetted: private to the business (S-123)
        mvc.perform(get("/api/v1/public/catalogue/media/{id}", main)).andExpect(status().isNotFound());

        jdbc.sql("update catalogue.services set vetting = 'approved', status = 'live' where id = :id")
                .param("id", id)
                .update();
        mvc.perform(get("/api/v1/public/catalogue/media/{id}", main)).andExpect(status().isOk());
        assertThat(offers.find(id, "en").map(ServiceOffers.Offer::imageUrl))
                .contains("/api/v1/public/catalogue/media/" + main);
    }

    @Test
    void savingWithoutPhotosClearsThem_andTheTableHasNoImage() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var photo = upload(biz, Color.GRAY);
        var id = json(mvc.perform(postJson(SERVICES, service("[\"%s\"]".formatted(photo)), biz.merchantId())
                        .with(TestJwt.member(biz.userId()))))
                .get("id")
                .asString();
        mvc.perform(putJson(SERVICES + "/{id}", service("[]"), biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images").isEmpty());
        mvc.perform(get("/api/v1/merchants/{m}/listings?kind=service", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());
    }

    @Test
    void anotherBusinesssPhoto_orTooMany_is422WithTheMessage() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var other = provider(MerchantRole.OWNER);
        var theirs = upload(other, Color.ORANGE);
        mvc.perform(postJson(SERVICES, service("[\"%s\"]".formatted(theirs)), biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("imageIds"))
                .andExpect(
                        jsonPath("$.errors[0].message").value("That image is no longer available — upload it again."));
        var ten = "[" + String.join(",", Collections.nCopies(10, "\"x\"")) + "]";
        mvc.perform(postJson(SERVICES, service(ten), biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("imageIds"))
                .andExpect(jsonPath("$.errors[0].message").value("Main image plus up to 8 more."));
    }

    @Test
    void onlyEditorsSavePhotos_andOnlyWithMfa() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var photo = upload(biz, Color.PINK);
        var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
        mvc.perform(postJson(SERVICES, service("[\"%s\"]".formatted(photo)), biz.merchantId())
                        .with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(postJson(SERVICES, service("[\"%s\"]".formatted(photo)), biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden());
    }
}
