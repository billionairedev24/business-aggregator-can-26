package ca.northline.merchants;

import static ca.northline.merchants.OnboardingApiTest.err;
import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.StorefrontPublished;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/** Page builder API (validation-rules.md › Storefront; storefront-sections.json). */
@RecordApplicationEvents
class StorefrontApiTest extends IntegrationTest {

    private static volatile boolean seeded;

    @Autowired
    ApplicationEvents events;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    OnboardingFlow flow;
    String owner;
    String merchantId;

    @BeforeEach
    void setUp() throws Exception {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc);
        owner = data.user("Ravi");
        merchantId = flow.start(owner, "provider");
        flow.business(merchantId, owner, soleBusiness("Aspen Wrench", "service.automotive.mobile-mechanic"))
                .andExpect(status().isOk());
    }

    ResultActions patchPage(String json) throws Exception {
        return mvc.perform(patch("/api/v1/merchants/{id}/storefront", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(owner)));
    }

    ResultActions patchSections(String json) throws Exception {
        return mvc.perform(patch("/api/v1/merchants/{id}/storefront/sections", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(owner)));
    }

    static String sections(String... kindsAndFlags) {
        var out = new StringBuilder("{\"sections\":[");
        for (int i = 0; i < kindsAndFlags.length; i++) {
            var parts = kindsAndFlags[i].split(":");
            out.append(i == 0 ? "" : ",")
                    .append("{\"kind\":\"%s\",\"enabled\":%s}"
                            .formatted(parts[0], parts.length > 1 ? parts[1] : "true"));
        }
        return out.append("]}").toString();
    }

    @Nested
    class Read {

        @Test
        void recommendedSectionsAllOn_withDefaults() throws Exception {
            mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.member(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("northline.ca/aspen-wrench")))
                    .andExpect(jsonPath("$.pageKind").value("business_page"))
                    .andExpect(jsonPath("$.brandColor").value("#2f5d3a"))
                    .andExpect(jsonPath("$.brandContrast").value(7.6))
                    .andExpect(jsonPath("$.ctaLabel").value("book_visit"))
                    .andExpect(jsonPath("$.publishedAt").doesNotExist())
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains("hero", "about", "services", "reviews", "area", "gallery", "faq", "cta")))
                    .andExpect(jsonPath("$.sections[0].required").value(true))
                    .andExpect(jsonPath("$.sections[1].required").value(false))
                    .andExpect(jsonPath("$.sections[?(@.enabled == false)]", hasSize(0)))
                    .andExpect(jsonPath("$.business.displayName").value("Aspen Wrench"))
                    .andExpect(jsonPath("$.business.status").value("applicant"));
        }

        @Test
        void everyMemberReads_strangersDoNot() throws Exception {
            var tech = data.user("Tech");
            data.member(merchantId, tech, MerchantRole.TECHNICIAN);
            mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.member(tech)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.member(data.user("X"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.memberWithoutMfa(owner)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void kitchensGetAMenuPage_bothGetTheUnion() throws Exception {
            var kitchen = flow.start(owner, "kitchen");
            flow.business(kitchen, owner, soleBusiness("Pho Aspen", "food.format.takeout-only"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchants/{id}/storefront", kitchen).with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.pageKind").value("menu_page"))
                    .andExpect(jsonPath("$.ctaLabel").value("order_now"))
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains("hero", "about", "menu", "hours", "fulfil", "reviews", "permit", "cta")));

            var both = flow.start(owner, "both");
            flow.business(both, owner, soleBusiness("Wrench & Parts", "shop.hardware-and-auto.tires"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchants/{id}/storefront", both).with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.pageKind").value("business_page"))
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains(
                                    "hero",
                                    "about",
                                    "services",
                                    "featured",
                                    "catalogue",
                                    "reviews",
                                    "area",
                                    "policies",
                                    "cta")));
        }

        @Test
        void createIsIdempotent() throws Exception {
            String first = JsonPath.read(
                    mvc.perform(post("/api/v1/merchants/{id}/storefront", merchantId)
                                    .with(TestJwt.member(owner)))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.id");
            mvc.perform(post("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.id").value(first));
        }

        @Test
        void slugsAreUniqueAcrossBusinesses() throws Exception {
            var twin = flow.start(owner, "provider");
            flow.business(twin, owner, soleBusiness("Aspen Wrench", "service.pets.dog-walker"))
                    .andExpect(status().isOk());
            String a = JsonPath.read(
                    mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId)
                                    .with(TestJwt.member(owner)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.slug");
            String b = JsonPath.read(
                    mvc.perform(get("/api/v1/merchants/{id}/storefront", twin).with(TestJwt.member(owner)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.slug");
            assertThat(a).isNotEqualTo(b);
            assertThat(b).matches("^[a-z0-9-]{3,40}$");
        }
    }

    @Nested
    class Edit {

        @Test
        void savesBrandTaglineCtaAnnouncement() throws Exception {
            patchPage("""
                            {"brandColor":"#9A4A1F","tagline":"Mobile mechanic · Calgary & Airdrie","ctaLabel":"request_quote",
                             "announcement":"Winter tire swaps: book before Oct 15 for $99"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.brandColor").value("#9a4a1f"))
                    .andExpect(jsonPath("$.brandContrast").value(6.2))
                    .andExpect(jsonPath("$.tagline").value("Mobile mechanic · Calgary & Airdrie"))
                    .andExpect(jsonPath("$.ctaLabel").value("request_quote"))
                    .andExpect(jsonPath("$.announcement").value("Winter tire swaps: book before Oct 15 for $99"));

            patchPage("{\"tagline\":\"\"}")
                    .andExpect(jsonPath("$.tagline").doesNotExist())
                    .andExpect(jsonPath("$.ctaLabel").value("request_quote"));
        }

        @ParameterizedTest(name = "[{index}] {1} → {2}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"brandColor\":\"#ffcc00\"}   | brandColor   | White text needs 4.5:1 contrast — pick a darker colour.",
                    "{\"brandColor\":\"green\"}     | brandColor   | Pick a colour like #2F5D3A.",
                    "{\"ctaLabel\":\"buy\"}         | ctaLabel     | Pick one of the labels.",
                    "{\"slug\":\"Aspen Wrench\"}  | slug         | Use 3–40 lowercase letters, numbers or hyphens.",
                    "{\"slug\":\"ab\"}              | slug         | Use 3–40 lowercase letters, numbers or hyphens.",
                    "{\"customDomain\":\"not a domain\"} | customDomain | Enter a domain like book.yourbusiness.ca.",
                    "{\"customDomain\":\"shop.northline.ca\"} | customDomain | Enter a domain like book.yourbusiness.ca.",
                    "{\"logoDocumentId\":\"01J9ZD3V000000000000000000\"} | logoDocumentId | Upload an SVG or PNG under 10 MB.",
                })
        void validatesEveryField(String json, String field, String message) throws Exception {
            patchPage(json).andExpect(status().isUnprocessableContent()).andExpect(err(field, message));
        }

        @Test
        void taglineIsAtMost80() throws Exception {
            patchPage("{\"tagline\":\"%s\"}".formatted("x".repeat(81)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("tagline", "At most 80 characters."));
            patchPage("{\"tagline\":\"%s\"}".formatted("x".repeat(80))).andExpect(status().isOk());
        }

        @Test
        void slugAndDomainAreUnique() throws Exception {
            var other = flow.start(owner, "provider");
            flow.business(other, owner, soleBusiness("Other Co", "service.pets.dog-walker"))
                    .andExpect(status().isOk());
            var domain = "book-" + merchantId.toLowerCase() + ".example.ca";
            patchPage("{\"slug\":\"sf-%s\",\"customDomain\":\"%s\"}".formatted(merchantId.toLowerCase(), domain))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"));
            mvc.perform(patch("/api/v1/merchants/{id}/storefront", other)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slug\":\"sf-%s\",\"customDomain\":\"%s\"}"
                                    .formatted(merchantId.toLowerCase(), domain))
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("slug", "That address is taken."))
                    .andExpect(err("customDomain", "That domain is already connected to another page."));
        }

        @Test
        void logoUploadsAndLinks() throws Exception {
            var file = new MockMultipartFile(
                    "file", "logo.svg", "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes());
            String logoId = JsonPath.read(
                    mvc.perform(multipart("/api/v1/merchants/{id}/documents", merchantId)
                                    .file(file)
                                    .param("purpose", "logo")
                                    .with(TestJwt.member(owner)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.id");
            patchPage("{\"logoDocumentId\":\"%s\"}".formatted(logoId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logo.id").value(logoId))
                    .andExpect(jsonPath("$.logo.url")
                            .value("/api/v1/merchants/%s/documents/%s".formatted(merchantId, logoId)));
            mvc.perform(get("/api/v1/merchants/{id}/documents/{doc}", merchantId, logoId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/svg+xml"));
            patchPage("{\"logoDocumentId\":\"\"}").andExpect(jsonPath("$.logo").doesNotExist());
        }

        @Test
        void onlyTheOwnerEdits() throws Exception {
            var tech = data.user("Tech");
            data.member(merchantId, tech, MerchantRole.TECHNICIAN);
            mvc.perform(patch("/api/v1/merchants/{id}/storefront", merchantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tagline\":\"x\"}")
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
    }

    @Nested
    class Sections {

        @Test
        void reorderAndToggleInOnePatch() throws Exception {
            patchSections(sections("hero", "services", "about:false", "faq", "reviews", "area", "gallery:false", "cta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains("hero", "services", "about", "faq", "reviews", "area", "gallery", "cta")))
                    .andExpect(jsonPath("$.sections[*].position", contains(0, 1, 2, 3, 4, 5, 6, 7)))
                    .andExpect(jsonPath("$.sections[2].enabled").value(false))
                    .andExpect(jsonPath("$.sections[6].enabled").value(false));

            // back to the recommended order ("Reset to recommended order" sends the default list)
            patchSections(sections("hero", "about", "services", "reviews", "area", "gallery", "faq", "cta"))
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains("hero", "about", "services", "reviews", "area", "gallery", "faq", "cta")))
                    .andExpect(jsonPath("$.sections[?(@.enabled == false)]", hasSize(0)));
        }

        @Test
        void heroAndCtaAreAlwaysOn() throws Exception {
            patchSections(sections("hero:false", "about", "services", "reviews", "area", "gallery", "faq", "cta:false"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("sections[0].enabled", "Header and Book / order button are always on."))
                    .andExpect(err("sections[7].enabled", "Header and Book / order button are always on."));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "missing a section        | hero,about,services,reviews,area,gallery,cta",
                    "duplicate section        | hero,about,about,reviews,area,gallery,faq,cta",
                    "kind of another page     | hero,about,services,reviews,area,gallery,menu,cta",
                })
        void theFullListOfThisPage(String name, String kinds) throws Exception {
            patchSections(sections(kinds.split(",")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("sections", "Send every section of this page exactly once."));
        }

        @Test
        void settingsLimits() throws Exception {
            var pairs = "[" + "{\"q\":\"q\",\"a\":\"a\"},".repeat(8) + "{\"q\":\"q\",\"a\":\"a\"}]";
            patchSections("""
                            {"sections":[{"kind":"hero","enabled":true},{"kind":"about","enabled":true},
                              {"kind":"services","enabled":true},{"kind":"reviews","enabled":true},{"kind":"area","enabled":true},
                              {"kind":"gallery","enabled":true},{"kind":"faq","enabled":true,"settings":{"pairs":%s}},
                              {"kind":"cta","enabled":true}]}""".formatted(pairs))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("sections[6].settings", "Up to 8 questions."));
        }

        @Test
        void technicianCannotReorder() throws Exception {
            var tech = data.user("Tech");
            data.member(merchantId, tech, MerchantRole.TECHNICIAN);
            mvc.perform(patch("/api/v1/merchants/{id}/storefront/sections", merchantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sections("hero", "about", "services", "reviews", "area", "gallery", "faq", "cta"))
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class Publish {

        void approve() {
            jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                    .param(merchantId)
                    .update();
        }

        @Test
        void onlyApprovedBusinessesGoLive() throws Exception {
            mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_approved"));
            assertThat(events.stream(StorefrontPublished.class)).isEmpty();
        }

        @Test
        void publishes_andAnnouncesIt() throws Exception {
            approve();
            patchSections(sections("hero", "about", "services", "reviews", "area", "gallery:false", "faq", "cta"));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publishedAt").exists());
            assertThat(events.stream(StorefrontPublished.class)).singleElement().satisfies(e -> {
                assertThat(e.merchantId()).isEqualTo(merchantId);
                assertThat(e.actorId()).isEqualTo(owner);
                assertThat(e.pageKind()).isEqualTo("business_page");
                assertThat(e.sections()).containsExactly("hero", "about", "services", "reviews", "area", "faq", "cta");
            });

            String slug = JsonPath.read(
                    mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId)
                                    .with(TestJwt.member(owner)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.slug");
            mvc.perform(get("/api/v1/storefronts/{slug}", slug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.business.displayName").value("Aspen Wrench"))
                    .andExpect(jsonPath("$.sections", hasSize(7)))
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains("hero", "about", "services", "reviews", "area", "faq", "cta")));
        }

        @Test
        void customDomainMustBeVerifiedFirst() throws Exception {
            approve();
            patchPage("{\"customDomain\":\"pending-%s.example.ca\"}".formatted(merchantId.toLowerCase()))
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err(
                            "customDomain", "Point the CNAME at pages.northline.ca and verify it before publishing."));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"));

            patchPage("{\"customDomain\":\"book-%s.example.ca\"}".formatted(merchantId.toLowerCase()));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.customDomainStatus").value("verified"));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isOk());
        }

        @Test
        void unpublishedPagesAreNotPublic() throws Exception {
            String slug = JsonPath.read(
                    mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId)
                                    .with(TestJwt.member(owner)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.slug");
            mvc.perform(get("/api/v1/storefronts/{slug}", slug)).andExpect(status().isNotFound());
        }
    }
}
