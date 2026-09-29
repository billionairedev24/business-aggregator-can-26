package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ListingFlagged;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.catalogue.api.ListingSubmitted;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Submit for vetting → automated checks (banned category, price outlier ±60 %, missing licence, duplicate image, main
 * image on white) → approved + {@code listing.published}, or flagged. And the image upload standards.
 */
class VettingAndMediaApiTest extends CatalogueApiTest {

    static final String LISTING = "/api/v1/merchants/{m}/listings/{id}";

    String upload(Business biz, byte[] bytes) throws Exception {
        return json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                                .file(new MockMultipartFile("file", "photo.png", "image/png", bytes))
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    /** A complete product with one own image, in {@code categoryId} (no required attributes unless auto parts). */
    String product(Business biz, String categoryId, long priceCents, String imageId) throws Exception {
        var attributes = categoryId.equals(AUTO_PARTS)
                ? "{\"partType\":\"Brakes\",\"length\":\"n/a\",\"position\":\"Front\"}"
                : "{}";
        var body = """
                {"identifierType":"none","title":"Test item","categoryId":"%s","attributes":%s,"priceCents":%d,"stock":3,
                 "imageSource":"own","imageIds":["%s"],"fulfilment":["pickup"],"countryOfOrigin":"CA",
                 "restrictedOk":true,"bilingualOk":true}
                """.formatted(categoryId, attributes, priceCents, imageId);
        return json(mvc.perform(postJson("/api/v1/merchants/{m}/products", body, biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    void submit(Business biz, String id) throws Exception {
        mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetting").value("pending"));
        assertThat(captured.of(ListingSubmitted.class, id)).hasSize(1);
    }

    void awaitFlags(Business biz, String id, String... flags) {
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("pending"))
                .andExpect(
                        jsonPath("$.vettingFlags").value(org.hamcrest.Matchers.containsInAnyOrder((Object[]) flags))));
        assertThat(captured.of(ListingFlagged.class, id))
                .singleElement()
                .satisfies(e -> assertThat(e.flags()).containsExactlyInAnyOrder(flags));
        assertThat(captured.of(ListingPublished.class, id)).isEmpty();
    }

    void awaitApproved(Business biz, String id) {
        await(() -> mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.vetting").value("approved"))
                .andExpect(jsonPath("$.status").value("live")));
        await(() -> assertThat(captured.of(ListingPublished.class, id))
                .singleElement()
                .satisfies(e -> assertThat(e.merchantId()).isEqualTo(biz.merchantId())));
    }

    @Nested
    class Vetting {

        @Test
        void cleanProductIsApprovedAndPublished() throws Exception {
            var biz = seller(MerchantRole.TECHNICIAN);
            var id = product(biz, AUTO_PARTS, 2000, upload(biz, png(1200, 1200, Color.WHITE)));
            submit(biz, id);
            awaitApproved(biz, id);
            // resubmitting an approved listing is a conflict
            mvc.perform(post(LISTING + "/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_submittable"));
        }

        @Test
        void priceOutsideSixtyPercentOfTheCategoryMedianIsFlagged() throws Exception {
            var computers = "shop.electronics.computers";
            approvedComparable(computers, 100_000);
            approvedComparable(computers, 100_000);
            approvedComparable(computers, 100_000);
            var biz = seller(MerchantRole.OWNER);
            var id = product(biz, computers, 10_000, upload(biz, png(1100, 1000, Color.WHITE)));
            submit(biz, id);
            awaitFlags(biz, id, "price_outlier");
        }

        @Test
        void bannedCategoryIsFlagged() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = product(
                    biz, "shop.restricted.cannabis-accessories", 2500, upload(biz, png(1000, 1000, Color.WHITE)));
            submit(biz, id);
            awaitFlags(biz, id, "banned_category");
        }

        @Test
        void regulatedCategoryNeedsAVerifiedLicence() throws Exception {
            var unlicensed = seller(MerchantRole.OWNER);
            var id = product(
                    unlicensed, "shop.restricted.alcohol", 3000, upload(unlicensed, png(1000, 1200, Color.WHITE)));
            submit(unlicensed, id);
            awaitFlags(unlicensed, id, "missing_licence");

            var licensed = seller(MerchantRole.OWNER);
            verifiedLicence(licensed.merchantId(), "AGLC");
            var ok = product(licensed, "shop.restricted.alcohol", 3000, upload(licensed, png(1000, 1300, Color.WHITE)));
            submit(licensed, ok);
            awaitApproved(licensed, ok);
        }

        @Test
        void duplicateOfAnotherMerchantsImageIsFlagged() throws Exception {
            var image = png(1300, 1300, Color.WHITE);
            var original = seller(MerchantRole.OWNER);
            upload(original, image);
            var copier = seller(MerchantRole.OWNER);
            var id = product(copier, AUTO_PARTS, 2000, upload(copier, image));
            submit(copier, id);
            awaitFlags(copier, id, "duplicate_image");
        }

        @Test
        void mainImageMustBeOnWhite() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = product(biz, AUTO_PARTS, 2000, upload(biz, png(1000, 1000, new Color(90, 20, 140))));
            submit(biz, id);
            awaitFlags(biz, id, "main_not_on_white");
        }

        @Test
        void serviceInARegulatedCategoryNeedsTheLicence() throws Exception {
            var body = """
                    {"name":"Diagnostic scan","categoryId":"%s","pricingMode":"fixed","priceCents":12000,"durationMin":60,
                     "bufferMin":20,"included":"OBD-II scan and report","instantBook":true}
                    """.formatted(MECHANIC);
            var unlicensed = provider(MerchantRole.OWNER);
            var flagged = json(mvc.perform(postJson("/api/v1/merchants/{m}/services", body, unlicensed.merchantId())
                            .with(TestJwt.member(unlicensed.userId()))))
                    .get("id")
                    .asString();
            submit(unlicensed, flagged);
            awaitFlags(unlicensed, flagged, "missing_licence");

            var licensed = provider(MerchantRole.OWNER);
            verifiedLicence(licensed.merchantId(), "AMVIC");
            var approved = json(mvc.perform(postJson("/api/v1/merchants/{m}/services", body, licensed.merchantId())
                            .with(TestJwt.member(licensed.userId()))))
                    .get("id")
                    .asString();
            submit(licensed, approved);
            awaitApproved(licensed, approved);
        }
    }

    @Nested
    class Media {

        @Test
        void uploadMeasuresTheImage_andServesItBack() throws Exception {
            var biz = seller(MerchantRole.TECHNICIAN);
            var bytes = png(1200, 900, Color.WHITE);
            var result = json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                            .file(new MockMultipartFile("file", "p.png", "image/png", bytes))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.width").value(1200))
                    .andExpect(jsonPath("$.height").value(900))
                    .andExpect(jsonPath("$.onWhite").value(true)));
            mvc.perform(get(result.get("url").asString()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"))
                    .andExpect(content().bytes(bytes));
        }

        @Test
        void imagesUnder1000PxAreRejected() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                            .file(new MockMultipartFile("file", "p.png", "image/png", png(999, 800, Color.WHITE)))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("file"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Images must be at least 1000 px on the longest side."));
        }

        @Test
        void nonImagesAndMissingFilesAreRejected() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                            .file(new MockMultipartFile(
                                    "file", "x.gif", "image/gif", "GIF89a....".getBytes(StandardCharsets.UTF_8)))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Use a JPG or PNG image."));
            mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                            .file(new MockMultipartFile("file", "x.png", "image/png", new byte[0]))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose an image to upload."));
        }

        @Test
        void bookkeeperCannotUpload_andAnotherMerchantsImageCannotBeAttached() throws Exception {
            var owner = seller(MerchantRole.OWNER);
            var bookkeeper = member(owner.merchantId(), MerchantRole.BOOKKEEPER);
            mvc.perform(multipart("/api/v1/merchants/{m}/media", owner.merchantId())
                            .file(new MockMultipartFile("file", "p.png", "image/png", png(1000, 1000, Color.WHITE)))
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());

            var other = seller(MerchantRole.OWNER);
            var foreign = upload(other, png(1000, 1001, Color.WHITE));
            mvc.perform(postJson(
                                    "/api/v1/merchants/{m}/products",
                                    """
                                    {"title":"Wiper","categoryId":"%s","priceCents":2000,"stock":1,"imageIds":["%s"]}
                                    """.formatted(AUTO_PARTS, foreign),
                                    owner.merchantId())
                            .with(TestJwt.member(owner.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("images"));
        }
    }
}
