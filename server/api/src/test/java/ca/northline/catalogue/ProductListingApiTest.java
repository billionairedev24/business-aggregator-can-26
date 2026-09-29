package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.api.ListingDeleted;
import ca.northline.catalogue.api.ListingHidden;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestJwt;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Product listings: the contract endpoints, the editor save, role gating, validation messages, lifecycle. */
class ProductListingApiTest extends CatalogueApiTest {

    static final String LISTINGS = "/api/v1/merchants/{m}/listings";
    static final String PRODUCTS = "/api/v1/merchants/{m}/products";

    @Autowired
    java.util.List<NavBadgeContributor> badgeContributors;

    String create(String merchantId, String userId, String body) throws Exception {
        return json(mvc.perform(postJson(PRODUCTS, body, merchantId).with(TestJwt.member(userId)))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    @Nested
    class Contract {

        @Test
        void onboardingQuickForm_createsADraftOnASellerOwnedRecord() throws Exception {
            var biz = seller(MerchantRole.OWNER);

            mvc.perform(postJson(PRODUCTS, """
                                    {"title":"Cabin air filter","categoryId":"%s","priceCents":2400,"stock":0}
                                    """.formatted(AUTO_PARTS), biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value("product"))
                    .andExpect(jsonPath("$.vetting").value("draft"))
                    .andExpect(jsonPath("$.status").value("hidden"))
                    .andExpect(jsonPath("$.identifierType").value("none"))
                    .andExpect(jsonPath("$.sharedRecord").value(false))
                    .andExpect(jsonPath("$.contentShared").value(false))
                    .andExpect(jsonPath("$.sku").value("CAF"))
                    .andExpect(jsonPath("$.catalogRef").value(org.hamcrest.Matchers.startsWith("NL-P-")))
                    .andExpect(jsonPath("$.completeness.total").value(6))
                    .andExpect(jsonPath("$.completeness.missing[*].field")
                            .value(org.hamcrest.Matchers.hasItems("attributes.partType", "images", "countryOfOrigin")));
        }

        @Test
        void listReturnsTheContractShape_andFiltersByKind() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            create(biz.merchantId(), biz.userId(), completeProduct("Wiper blades 22", "WB-22", 1900));

            mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].kind").value("product"))
                    .andExpect(jsonPath("$.items[0].name").value("Wiper blades 22"))
                    .andExpect(jsonPath("$.items[0].sku").value("WB-22"))
                    .andExpect(jsonPath("$.items[0].meta").value("Auto parts"))
                    .andExpect(jsonPath("$.items[0].priceCents").value(1900))
                    .andExpect(jsonPath("$.items[0].stock").value(10))
                    .andExpect(jsonPath("$.items[0].sales30d").value(0))
                    .andExpect(jsonPath("$.items[0].vetting").value("draft"))
                    .andExpect(jsonPath("$.items[0].status").value("hidden"));
            mvc.perform(get(LISTINGS + "?kind=service&limit=5", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
            mvc.perform(get(LISTINGS + "?kind=dish", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        void gtinOfANewProductCreatesTheSharedRecord_andTheNextSellerInheritsIt() throws Exception {
            var first = seller(MerchantRole.OWNER);
            var second = seller(MerchantRole.OWNER);
            var gtin = "040112456784"; // GTIN-12 with a valid check digit
            var body = """
                    {"gtin":"%s","title":"Bosch Aerotwin 24","brand":"Bosch","categoryId":"%s","priceCents":2100,"stock":4,
                     "attributes":{"partType":"Wiper blades","length":"24 in","position":"Front"}}
                    """.formatted(gtin, AUTO_PARTS);
            create(first.merchantId(), first.userId(), body);

            mvc.perform(get(
                                    "/api/v1/merchants/{m}/catalogue/products/lookup?gtin={g}",
                                    second.merchantId(),
                                    "040112456784")
                            .with(TestJwt.member(second.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Bosch Aerotwin 24"))
                    .andExpect(jsonPath("$.sellerCount").value(1))
                    .andExpect(jsonPath("$.attributes.length").value("24 in"));

            var id = create(second.merchantId(), second.userId(), """
                    {"gtin":"040112456784","title":"My wiper","brand":"Other","categoryId":"%s","priceCents":2000,"stock":1}
                    """.formatted(AUTO_PARTS));
            mvc.perform(get(LISTINGS + "/{id}", second.merchantId(), id).with(TestJwt.member(second.userId())))
                    .andExpect(jsonPath("$.contentShared").value(true))
                    .andExpect(jsonPath("$.sharedRecord").value(true))
                    .andExpect(jsonPath("$.brand").value("Bosch"))
                    .andExpect(jsonPath("$.attributes.position").value("Front"))
                    .andExpect(jsonPath("$.sellerCount").value(2))
                    .andExpect(jsonPath("$.imageSource").value("shared"));
        }

        @Test
        void lookupUnknownGtinIs404_malformedIs422() throws Exception {
            var biz = seller(MerchantRole.TECHNICIAN);
            mvc.perform(get("/api/v1/merchants/{m}/catalogue/products/lookup?gtin=062760000454", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/merchants/{m}/catalogue/products/lookup?gtin=028851200220", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("GTIN check digit invalid"));
        }

        @Test
        void categoriesCarryAttributesThemesAndMedian() throws Exception {
            var biz = seller(MerchantRole.BOOKKEEPER);
            mvc.perform(get("/api/v1/merchants/{m}/catalogue/categories?root=shop", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].leaf".formatted(AUTO_PARTS))
                            .value(true))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].attributes[0].label".formatted(AUTO_PARTS))
                            .value("Part type"))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].variantThemes[0]".formatted(AUTO_PARTS))
                            .value("length"))
                    .andExpect(jsonPath("$.items[?(@.id == 'shop.restricted.cannabis-accessories')].banned")
                            .value(true))
                    .andExpect(jsonPath("$.items[?(@.id == 'shop.hardware-and-auto')].leaf")
                            .value(false));
            mvc.perform(get("/api/v1/merchants/{m}/catalogue/categories?root=food", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    class Authorization {

        @Test
        void nonMemberIsForbidden() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
        }

        @Test
        void withoutMfaIsForbidden() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void bookkeeperReadsButCannotCreateOrPublish() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = create(biz.merchantId(), biz.userId(), completeProduct("Brake pads", "BP-1", 6800));
            var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);

            mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
            mvc.perform(get(LISTINGS + "/{id}", biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
            mvc.perform(postJson(PRODUCTS, completeProduct("X", "X-1", 1000), biz.merchantId())
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post(LISTINGS + "/{id}/hide", biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void technicianEditsButCannotDelete() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var tech = member(biz.merchantId(), MerchantRole.TECHNICIAN);
            var id = create(biz.merchantId(), tech, completeProduct("Oil 5W-30", "OIL-1", 4200));

            mvc.perform(putJson(
                                    PRODUCTS + "/{id}",
                                    completeProduct("Oil 5W-30 · 5 L", "OIL-1", 4200),
                                    biz.merchantId(),
                                    id)
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Oil 5W-30 · 5 L"));
            mvc.perform(delete(LISTINGS + "/{id}", biz.merchantId(), id).with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        @Test
        void otherMerchantsListingIs404() throws Exception {
            var mine = seller(MerchantRole.OWNER);
            var theirs = seller(MerchantRole.OWNER);
            var id = create(theirs.merchantId(), theirs.userId(), completeProduct("Theirs", "T-1", 1000));
            mvc.perform(get(LISTINGS + "/{id}", mine.merchantId(), id).with(TestJwt.member(mine.userId())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Validation {

        static java.util.stream.Stream<Arguments> invalidFields() {
            return java.util.stream.Stream.of(
                    Arguments.of("title", "\"\"", "Enter a product title."),
                    Arguments.of("title", "\"" + "W".repeat(81) + "\"", "At most 80 characters."),
                    Arguments.of(
                            "title", "\"Wiper blades · best price\"", "Leave out promo words like sale, free or best."),
                    Arguments.of("categoryId", "\"\"", "Choose a category."),
                    Arguments.of(
                            "categoryId", "\"shop.hardware-and-auto\"", "Choose a category down to the last level."),
                    Arguments.of("categoryId", "\"" + MECHANIC + "\"", "Category not allowed in Shop"),
                    Arguments.of("priceCents", "null", "Enter a price."),
                    Arguments.of("priceCents", "0", "Enter a price above $0."),
                    Arguments.of("stock", "null", "Enter stock on hand."),
                    Arguments.of("stock", "-1", "Stock can't be negative."),
                    Arguments.of("gtin", "\"12345\"", "GTIN must be 8, 12, 13 or 14 digits."),
                    Arguments.of("gtin", "\"028851200220\"", "GTIN check digit invalid"),
                    Arguments.of("compareAtCents", "1500", "Compare-at must be higher than your price."),
                    Arguments.of("costCents", "-5", "Cost can't be negative."),
                    Arguments.of("lowStockAt", "-1", "Alert level can't be negative."),
                    Arguments.of("returnsPolicy", "\"final_sale\"", "Final sale is allowed only for perishables."),
                    Arguments.of("searchKeywords", "\"" + "k".repeat(251) + "\"", "At most 250 characters."),
                    Arguments.of("bullets", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]", "Up to 5 bullet points."),
                    Arguments.of(
                            "images",
                            "[\"01J9ZD3V0000000000000NOPE\"]",
                            "That image is no longer available — upload it again."));
        }

        @ParameterizedTest(name = "[{index}] {0} → {2}")
        @MethodSource("invalidFields")
        void invalidFieldIs422WithItsMessage(String field, String jsonValue, String message) throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var property = "images".equals(field) ? "imageIds" : field;
            var body = """
                    {"title":"Wiper","categoryId":"%s","priceCents":2000,"stock":1,"%s":%s}
                    """.formatted(AUTO_PARTS, property, jsonValue);

            mvc.perform(postJson(PRODUCTS, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == '%s')].message".formatted(field))
                            .value(message));
        }

        @Test
        void variantRowsAreValidated() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var body = """
                    {"title":"Wiper","categoryId":"%s","priceCents":2000,"stock":1,"variantTheme":"length","variants":[
                      {"value":"20 in","sku":"","priceCents":1700,"stock":1},
                      {"value":"22 in","sku":"WB-22","gtin":"028851200220","priceCents":1900,"stock":1},
                      {"value":"22 in","sku":"WB-22","priceCents":0,"stock":-1}]}
                    """.formatted(AUTO_PARTS);
            mvc.perform(postJson(PRODUCTS, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[0].sku')].message")
                            .value("Enter a SKU."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[2].priceCents')].message")
                            .value("Enter a price above $0."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[2].stock')].message")
                            .value("Stock can't be negative."));

            var domainRules = """
                    {"title":"Wiper","categoryId":"%s","priceCents":2000,"stock":1,"variantTheme":"length","variants":[
                      {"value":"22 in","sku":"WB-22","gtin":"028851200220","priceCents":1900,"stock":1},
                      {"value":"22 in","sku":"WB-22","priceCents":1900,"stock":1}]}
                    """.formatted(AUTO_PARTS);
            mvc.perform(postJson(PRODUCTS, domainRules, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[0].gtin')].message")
                            .value("GTIN check digit invalid"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[1].value')].message")
                            .value("Each variant needs its own value."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'variants[1].sku')].message")
                            .value("Each variant needs its own SKU."));
        }

        @Test
        void skuMustBeUniqueWithinTheMerchant() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            create(biz.merchantId(), biz.userId(), completeProduct("Brake pads", "BP-9", 6800));
            mvc.perform(postJson(PRODUCTS, completeProduct("Other pads", "bp-9", 6000), biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("sku"))
                    .andExpect(jsonPath("$.errors[0].message").value("That SKU is already used by another listing."));
        }

        @Test
        void offerChecksBackTheSchemaConstraint() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> jdbc.sql("""
                                    insert into catalogue.offers (id, merchant_id, price_cents, vetting, status)
                                    values ('X' || gen_random_uuid(), 'm', -1, 'draft', 'hidden')
                                    """).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void submitIncompleteIs422WithWhatIsMissing() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = create(biz.merchantId(), biz.userId(), """
                    {"title":"Cabin air filter","categoryId":"%s","priceCents":2400,"stock":0}
                    """.formatted(AUTO_PARTS));
            mvc.perform(post(LISTINGS + "/{id}/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'attributes.partType')].message")
                            .value("Choose Part type."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'images')].message")
                            .value("Add a main image on white, at least 1000 px."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'countryOfOrigin')].message")
                            .value("Choose the country of origin."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'restrictedOk')].message")
                            .value("Confirm this is not a restricted product."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'bilingualOk')].message")
                            .value("Confirm bilingual labelling."));
        }

        @Test
        void publishingADraftIs409_editingAPendingListingWithdrawsIt() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = create(biz.merchantId(), biz.userId(), completeProduct("Wiper", "WB-P1", 2000));
            mvc.perform(post(LISTINGS + "/{id}/publish", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("listing_draft"));

            // No shared images and no own images → incomplete; switch to a seller-owned image-less draft and check
            // that an edit of a pending listing puts it back to draft.
            jdbc.sql("update catalogue.offers set vetting = 'pending', submitted_at = now() where id = ?")
                    .params(id)
                    .update();
            mvc.perform(putJson(PRODUCTS + "/{id}", completeProduct("Wiper 2", "WB-P1", 2000), biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vetting").value("draft"))
                    .andExpect(jsonPath("$.submittedAt").doesNotExist());
            mvc.perform(post(LISTINGS + "/{id}/submit", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        void hidePublishAndDeleteAnApprovedListing() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = create(biz.merchantId(), biz.userId(), completeProduct("Brake pads", "BP-L1", 6800));
            jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                    .params(id)
                    .update();

            mvc.perform(post(LISTINGS + "/{id}/hide", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            assertThat(captured.of(ListingHidden.class, id)).hasSize(1);
            mvc.perform(post(LISTINGS + "/{id}/publish", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            assertThat(captured.of(ListingPublished.class, id)).singleElement().satisfies(e -> {
                assertThat(e.merchantId()).isEqualTo(biz.merchantId());
                assertThat(e.kind()).isEqualTo("product");
            });
            mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].status").value("live"));

            mvc.perform(delete(LISTINGS + "/{id}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            assertThat(captured.of(ListingDeleted.class, id))
                    .singleElement()
                    .satisfies(e -> assertThat(e.actorId()).isEqualTo(biz.userId()));
            mvc.perform(get(LISTINGS + "/{id}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void navBadgeCountsListings() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            create(biz.merchantId(), biz.userId(), completeProduct("A", "A-1", 1000));
            create(biz.merchantId(), biz.userId(), completeProduct("B", "B-1", 1000));

            var badges = badgeContributors.stream()
                    .map(c -> c.badges(new NavBadgeContributor.Context(
                            biz.merchantId(),
                            biz.userId(),
                            ca.northline.shared.security.MerchantRole.OWNER,
                            Locale.CANADA)))
                    .filter(b -> b.containsKey("products"))
                    .toList();
            assertThat(badges)
                    .singleElement()
                    .satisfies(b -> assertThat(b.get("products")).isEqualTo("2"));
        }
    }
}
