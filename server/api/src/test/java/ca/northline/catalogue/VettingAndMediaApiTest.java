package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /** S-123: another business's images are served only once approved; the storefront path only serves approved ones. */
    @Nested
    class Ownership {

        static final String MEDIA = "/api/v1/merchants/{m}/media/{id}";
        static final String PUBLIC = "/api/v1/public/catalogue/media/{id}";

        @Test
        void draftImageIs403ForAnotherBusiness_andNotPublic() throws Exception {
            var owner = seller(MerchantRole.OWNER);
            var bytes = png(1200, 1200, Color.WHITE);
            var draft = upload(owner, bytes);
            product(owner, AUTO_PARTS, 2000, draft);
            var other = seller(MerchantRole.OWNER);

            mvc.perform(get(MEDIA, owner.merchantId(), draft).with(TestJwt.member(owner.userId())))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(bytes));
            // the other business's own Studio path: the key is known, the image isn't theirs
            mvc.perform(get(MEDIA, other.merchantId(), draft).with(TestJwt.member(other.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"))
                    .andExpect(jsonPath("$.detail")
                            .value("This image belongs to another business and hasn't been approved yet."));
            // the owner's path: not a member
            mvc.perform(get(MEDIA, owner.merchantId(), draft).with(TestJwt.member(other.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get(PUBLIC, draft)).andExpect(status().isNotFound());
            mvc.perform(get(MEDIA, other.merchantId(), "01J9ZD3V0000000000000NOPE1")
                            .with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void approvedImageIsPublic_andVisibleToOtherBusinesses_otherDraftsStayPrivate() throws Exception {
            var owner = seller(MerchantRole.OWNER);
            var bytes = png(1250, 1250, Color.WHITE);
            var image = upload(owner, bytes);
            var id = product(owner, AUTO_PARTS, 2000, image);
            submit(owner, id);
            awaitApproved(owner, id);
            var unused = upload(owner, png(1260, 1260, Color.WHITE));
            var other = seller(MerchantRole.OWNER);

            mvc.perform(get(PUBLIC, image))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(bytes))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
            mvc.perform(get(MEDIA, other.merchantId(), image).with(TestJwt.member(other.userId())))
                    .andExpect(status().isOk());
            mvc.perform(get(MEDIA, other.merchantId(), unused).with(TestJwt.member(other.userId())))
                    .andExpect(status().isForbidden());
            mvc.perform(get(PUBLIC, unused)).andExpect(status().isNotFound());
        }

        @Test
        void imagesOfALockedCatalogueRecordAreApproved() throws Exception {
            var brand = seller(MerchantRole.OWNER);
            var image = upload(brand, png(1300, 1100, Color.WHITE));
            var recordId = ca.northline.shared.Ids.next();
            jdbc.sql("""
                            insert into catalogue.catalog_products (id, ref, identifier_type, title, category_id, attributes,
                              image_set, locked)
                            values (?, ?, 'none', 'Brand record', ?, '{}', ?, true)
                            """)
                    .params(recordId, "T-" + recordId, AUTO_PARTS, new String[] {image})
                    .update();
            var other = seller(MerchantRole.OWNER);
            mvc.perform(get(MEDIA, other.merchantId(), image).with(TestJwt.member(other.userId())))
                    .andExpect(status().isOk());
            mvc.perform(get(PUBLIC, image)).andExpect(status().isOk());
        }

        @Test
        void gtinLookupLeavesOutTheFirstSellersUnvettedImages_untilApproved() throws Exception {
            var first = seller(MerchantRole.OWNER);
            var gtin = randomGtin13();
            var image = upload(first, png(1400, 1400, Color.WHITE));
            var body = """
                    {"identifierType":"gtin","gtin":"%s","title":"New wiper","brand":"Acme","categoryId":"%s",
                     "attributes":{"partType":"Brakes","length":"n/a","position":"Front"},"priceCents":2000,"stock":3,
                     "imageSource":"own","imageIds":["%s"],"fulfilment":["pickup"],"countryOfOrigin":"CA",
                     "restrictedOk":true,"bilingualOk":true}
                    """.formatted(gtin, AUTO_PARTS, image);
            var id = json(mvc.perform(postJson("/api/v1/merchants/{m}/products", body, first.merchantId())
                                    .with(TestJwt.member(first.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            var second = seller(MerchantRole.OWNER);
            var lookup = "/api/v1/merchants/{m}/catalogue/products/lookup?gtin={g}";

            mvc.perform(get(lookup, second.merchantId(), gtin).with(TestJwt.member(second.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images").isEmpty());
            mvc.perform(get(lookup, first.merchantId(), gtin).with(TestJwt.member(first.userId())))
                    .andExpect(jsonPath("$.images[0].id").value(image));

            submit(first, id);
            awaitApproved(first, id);
            mvc.perform(get(lookup, second.merchantId(), gtin).with(TestJwt.member(second.userId())))
                    .andExpect(jsonPath("$.images[0].id").value(image))
                    .andExpect(jsonPath("$.images[0].url")
                            .value("/api/v1/merchants/%s/media/%s".formatted(second.merchantId(), image)));
        }

        /** A GTIN-13 with a valid check digit in the 2xx (restricted circulation) range, fresh per call. */
        static String randomGtin13() {
            var random = java.util.concurrent.ThreadLocalRandom.current();
            var digits = new StringBuilder("2");
            for (int i = 0; i < 11; i++) {
                digits.append(random.nextInt(10));
            }
            var sum = 0;
            for (int i = 0; i < 12; i++) {
                sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
            }
            return digits.append((10 - sum % 10) % 10).toString();
        }
    }
}
