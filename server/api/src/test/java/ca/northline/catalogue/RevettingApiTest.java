package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ListingHidden;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.catalogue.api.ListingSubmitted;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.awt.Color;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * S-39 through the api: a price, category or image change on an approved listing sends it back to pending, the
 * automated checks run again, and it comes back live. Other edits leave it approved.
 */
class RevettingApiTest extends CatalogueApiTest {

    static final String LISTING = "/api/v1/merchants/{m}/listings/{id}";

    String upload(Business biz) throws Exception {
        return json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                                .file(new MockMultipartFile("file", "p.png", "image/png", png(1200, 1200, Color.WHITE)))
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    static String body(String title, long priceCents, String... imageIds) {
        return """
                {"identifierType":"none","title":"%s","categoryId":"%s",
                 "attributes":{"partType":"Brakes","length":"n/a","position":"Front"},"priceCents":%d,"stock":3,
                 "imageSource":"own","imageIds":[%s],"fulfilment":["pickup"],"countryOfOrigin":"CA",
                 "restrictedOk":true,"bilingualOk":true}
                """.formatted(
                        title,
                        AUTO_PARTS,
                        priceCents,
                        String.join(
                                ",",
                                java.util.Arrays.stream(imageIds)
                                        .map(i -> "\"" + i + "\"")
                                        .toList()));
    }

    /** A complete product with one own image, submitted and approved by the automated checks. */
    String approvedProduct(Business biz, String image) throws Exception {
        var id = json(mvc.perform(postJson(
                                        "/api/v1/merchants/{m}/products",
                                        body("Brake pads", 2000, image),
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
        mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        awaitApproved(biz, id, 1);
        return id;
    }

    void awaitApproved(Business biz, String id, int publishedTimes) {
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("approved"))
                .andExpect(jsonPath("$.status").value("live"))
                .andExpect(jsonPath("$.revetReasons").isEmpty()));
        await(() -> assertThat(captured.of(ListingPublished.class, id)).hasSize(publishedTimes));
    }

    @Test
    void aPriceChangeSendsTheListingBackToVetting_andItComesBackLive() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var image = upload(biz);
        var id = approvedProduct(biz, image);

        mvc.perform(putJson(
                                "/api/v1/merchants/{m}/products/{id}",
                                body("Brake pads", 2100, image),
                                biz.merchantId(),
                                id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.revetReasons").value(Matchers.contains("price")));
        assertThat(captured.of(ListingHidden.class, id)).hasSize(1);
        assertThat(captured.of(ListingSubmitted.class, id))
                .hasSize(2)
                .last()
                .satisfies(e -> assertThat(e.actorId()).isEqualTo(biz.userId()));

        awaitApproved(biz, id, 2);
    }

    @Test
    void newImagesAreMaterial_aNewTitleIsNot() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var image = upload(biz);
        var id = approvedProduct(biz, image);

        mvc.perform(putJson(
                                "/api/v1/merchants/{m}/products/{id}",
                                body("Brake pads · ceramic", 2000, image),
                                biz.merchantId(),
                                id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetting").value("approved"))
                .andExpect(jsonPath("$.revetReasons").isEmpty());
        assertThat(captured.of(ListingSubmitted.class, id)).hasSize(1);

        var second = upload(biz);
        mvc.perform(putJson(
                                "/api/v1/merchants/{m}/products/{id}",
                                body("Brake pads · ceramic", 2000, image, second),
                                biz.merchantId(),
                                id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.revetReasons").value(Matchers.contains("images")));
        awaitApproved(biz, id, 2);
    }

    @Test
    void aHiddenListingIsReVettedButStaysHidden() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var image = upload(biz);
        var id = approvedProduct(biz, image);
        mvc.perform(post(LISTING + "/hide", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(putJson(
                                "/api/v1/merchants/{m}/products/{id}",
                                body("Brake pads", 1900, image),
                                biz.merchantId(),
                                id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.status").value("hidden"));
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("approved"))
                .andExpect(jsonPath("$.status").value("hidden")));
        assertThat(captured.of(ListingPublished.class, id)).hasSize(1); // only the first approval
    }

    @Test
    void aServiceCategoryChangeIsReVetted_andFlaggedWhenTheLicenceIsMissing() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var service = """
                {"name":"Tire swap","categoryId":"%s","pricingMode":"fixed","priceCents":9900,"durationMin":60,
                 "bufferMin":15,"included":"Swap four mounted wheels","instantBook":true}
                """;
        var tires = "service.automotive.tire-change-and-storage";
        var id = json(mvc.perform(postJson("/api/v1/merchants/{m}/services", service.formatted(tires), biz.merchantId())
                        .with(TestJwt.member(biz.userId()))))
                .get("id")
                .asString();
        mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        awaitApproved(biz, id, 1);

        // a regulated category (AMVIC) without a licence: the re-vet flags it for the console
        mvc.perform(putJson("/api/v1/merchants/{m}/services/{id}", service.formatted(MECHANIC), biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.revetReasons").value(Matchers.contains("category")));
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(jsonPath("$.vettingFlags").value(Matchers.contains("missing_licence")))
                .andExpect(jsonPath("$.revetReasons").value(Matchers.contains("category"))));
        // the Listings table shows it as pending, with the reason
        mvc.perform(get("/api/v1/merchants/{m}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items[0].vetting").value("pending"))
                .andExpect(jsonPath("$.items[0].revetReasons[0]").value("category"));
    }
}
