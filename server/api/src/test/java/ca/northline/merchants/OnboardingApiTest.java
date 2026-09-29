package ca.northline.merchants;

import static ca.northline.merchants.OnboardingFlow.corpBusiness;
import static ca.northline.merchants.OnboardingFlow.randomBn;
import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.MerchantSubmitted;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Business onboarding wizard API (design 02 onboarding; validation-rules.md › Business step). */
@RecordApplicationEvents
class OnboardingApiTest extends IntegrationTest {

    private static volatile boolean seeded;

    @Autowired
    ApplicationEvents events;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    OnboardingFlow flow;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc);
    }

    @Nested
    class AccountStep {

        @Test
        void createsAnApplicantOwnedByTheCaller_withTheTypeChecklist() throws Exception {
            var user = data.user("Ravi Sandhu");

            mvc.perform(post("/api/v1/merchants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"provider","province":"AB","workEmail":"ravi@prairiewrench.ca",
                                     "businessTermsAccepted":true}""")
                            .with(TestJwt.member(user)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("provider"))
                    .andExpect(jsonPath("$.status").value("applicant"))
                    .andExpect(jsonPath("$.step").value("business"))
                    .andExpect(jsonPath("$.province").value("AB"))
                    .andExpect(jsonPath("$.workEmail").value("ravi@prairiewrench.ca"))
                    .andExpect(jsonPath("$.businessTermsAccepted").value(true))
                    .andExpect(jsonPath("$.business").doesNotExist())
                    .andExpect(jsonPath("$.checklist[*].key", contains("kyc", "registry", "insurance", "bank", "mfa")))
                    .andExpect(jsonPath("$.checksComplete").value(0));

            mvc.perform(get("/api/v1/me/businesses").with(TestJwt.member(user)))
                    .andExpect(jsonPath("$.items[0].displayName").value("New business"))
                    .andExpect(jsonPath("$.items[0].status").value("applicant"))
                    .andExpect(jsonPath("$.items[0].role").value("owner"));
        }

        @Test
        void kitchensGetTheFullAhsChecklist() throws Exception {
            var user = data.user("Ravi");
            var id = flow.start(user, "kitchen");
            assertThat(JsonPath.<List<String>>read(flow.onboarding(id, user), "$.checklist[*].key"))
                    .containsExactly(
                            "kyc",
                            "registry",
                            "ahs_permit",
                            "food_cert",
                            "inspection",
                            "insurance",
                            "allergen_attestation",
                            "aglc",
                            "gst",
                            "bank",
                            "mfa",
                            "site_visit");
        }

        @Test
        void sellersGetTheSellerChecklist() throws Exception {
            var user = data.user("Amara");
            var id = flow.start(user, "seller");
            assertThat(JsonPath.<List<String>>read(flow.onboarding(id, user), "$.checklist[*].key"))
                    .containsExactly(
                            "kyc",
                            "registry",
                            "gst",
                            "category_permits",
                            "product_safety",
                            "returns_policy",
                            "bank",
                            "mfa");
        }

        @Test
        void needsASecondFactor() throws Exception {
            mvc.perform(post("/api/v1/merchants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"provider\",\"province\":\"AB\"}")
                            .with(TestJwt.memberWithoutMfa(data.user("No MFA"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void needsTheMerchantScope() throws Exception {
            mvc.perform(post("/api/v1/merchants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"provider\",\"province\":\"AB\"}")
                            .with(TestJwt.customer(data.user("Customer"))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"province\":\"AB\"}                                     | type      | Pick what your business does on Northline.",
                    "{\"type\":\"seller\"}                                     | province  | Pick the province you operate in.",
                    "{\"type\":\"seller\",\"province\":\"AB\",\"workEmail\":\"nope\"} | workEmail | That doesn't look like an email address.",
                })
        void validatesTheAccountStep(String json, String field, String message) throws Exception {
            mvc.perform(post("/api/v1/merchants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .with(TestJwt.member(data.user("V"))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void changingTheTypeRebuildsChecklistAndPage() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            flow.business(id, user, soleBusiness("Glenmore Bakery", "service.pets.dog-walker"))
                    .andExpect(status().isOk());

            mvc.perform(put("/api/v1/merchants/{id}/onboarding/account", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"seller\",\"province\":\"BC\"}")
                            .with(TestJwt.member(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("seller"))
                    .andExpect(jsonPath("$.province").value("BC"))
                    .andExpect(jsonPath("$.business.categories", hasSize(0)))
                    .andExpect(jsonPath("$.checklist[2].key").value("gst"));

            mvc.perform(get("/api/v1/merchants/{id}/storefront", id).with(TestJwt.member(user)))
                    .andExpect(jsonPath("$.pageKind").value("store"))
                    .andExpect(jsonPath("$.ctaLabel").value("order_now"))
                    .andExpect(jsonPath(
                            "$.sections[*].kind",
                            contains(
                                    "hero",
                                    "about",
                                    "featured",
                                    "catalogue",
                                    "delivery",
                                    "reviews",
                                    "policies",
                                    "cta")));
        }
    }

    @Nested
    class BusinessStep {

        @Test
        void savesEverything_rebuildsTheChecklist_andCreatesThePage() throws Exception {
            var user = data.user("Ravi Sandhu");
            var id = flow.start(user, "provider");
            var doc = flow.upload(id, user, "legal");
            var bn = randomBn();

            flow.business(id, user, corpBusiness(doc, bn))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Aspen Wrench"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.step").value("verification"))
                    .andExpect(jsonPath("$.business.gstNumber").value("123456789 RT0001"))
                    .andExpect(jsonPath("$.business.legalDetails.structure").value("corp_ab"))
                    .andExpect(
                            jsonPath("$.business.legalDetails.business_number").value(bn))
                    .andExpect(jsonPath("$.business.principals", hasSize(2)))
                    .andExpect(jsonPath("$.business.principals[0].role").value("director"))
                    .andExpect(jsonPath("$.business.categories[*].name", hasItem("Mobile mechanic")))
                    .andExpect(jsonPath("$.business.categories[*].name", hasItem("Bike repair")))
                    .andExpect(jsonPath("$.business.documents[0].id").value(doc))
                    .andExpect(jsonPath("$.business.profile.serviceArea").value("Calgary + 40 km · Airdrie"))
                    .andExpect(jsonPath(
                            "$.checklist[*].key",
                            contains("kyc", "registry", "licence:AMVIC", "insurance", "bank", "mfa")))
                    .andExpect(jsonPath("$.checklist[2].registry").value("AMVIC"));

            assertThat(jdbc.sql(
                                    "select status from merchants.merchant_categories where merchant_id = ? order by category_id")
                            .param(id)
                            .query(String.class)
                            .list())
                    .containsExactly(
                            "requested",
                            "approved",
                            "requested"); // mobile mechanic (AMVIC) · house cleaning · suggestion
            assertThat(jdbc.sql("""
                            select count(*) from merchants.merchant_principals p
                              join merchants.verifications v on v.id = p.kyc_verification_id and v.check_key = 'kyc'
                             where p.merchant_id = ?""").param(id).query(Integer.class).single())
                    .as("principals ≥ 25 %% point at the KYC row")
                    .isEqualTo(2);
            mvc.perform(get("/api/v1/merchants/{id}/storefront", id).with(TestJwt.member(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value(org.hamcrest.Matchers.startsWith("aspen-wrench")))
                    .andExpect(jsonPath("$.pageKind").value("business_page"));
        }

        @Test
        void checklistKeepsEvidenceWhenCategoriesChange() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            flow.business(id, user, soleBusiness("Pipes Co", "service.home-trades.plumber"))
                    .andExpect(status().isOk());
            String kycId = JsonPath.read(flow.onboarding(id, user), "$.checklist[0].id");
            flow.complete(id, user, kycId, "{}").andExpect(status().isOk());

            flow.business(id, user, soleBusiness("Pipes Co", "service.pets.dog-walker"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checklist[0].id").value(kycId))
                    .andExpect(jsonPath("$.checklist[0].status").value("verified"))
                    .andExpect(jsonPath("$.checklist[*].key", contains("kyc", "registry", "insurance", "bank", "mfa")));
        }

        /** validation-rules.md › Business step — exact messages. */
        @ParameterizedTest(name = "[{index}] {1} → {2}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "displayName | ''  | displayName | Enter the name customers will see.",
                    "displayName | 'A' | displayName | At least 2 characters.",
                    "legalName   | ''  | legalName   | Enter the registered legal name.",
                })
        void namesAreValidated(String key, String value, String field, String message) throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "seller");
            var json = soleBusiness("Glenmore Bakery", "shop.food-and-grocery.bakery")
                    .replace(
                            key.equals("displayName")
                                    ? "\"displayName\":\"Glenmore Bakery\""
                                    : "\"legalName\":\"Amara Okafor\"",
                            "\"%s\":\"%s\"".formatted(key, value));
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == '%s')].message".formatted(field))
                            .value(message));
        }

        @Test
        void gstIsRequiredForCorporations() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            var doc = flow.upload(id, user, "legal");
            flow.business(id, user, corpBusiness(doc, randomBn()).replace("\"gstNumber\":\"123456789 rt0001\",", ""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("gstNumber"))
                    .andExpect(jsonPath("$.errors[0].rule").value("required"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Required for corporations, co-ops and non-profits."));
        }

        @Test
        void gstFormatIsChecked_evenWhenOptional() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "seller");
            var json = soleBusiness("Glenmore Bakery", "shop.food-and-grocery.bakery")
                    .replace("\"structure\":\"sole\",", "\"structure\":\"sole\",\"gstNumber\":\"12345 RT1\",");
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("gstNumber"))
                    .andExpect(jsonPath("$.errors[0].rule").value("format"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Format is 9 digits + RT0001 (e.g. 123456789 RT0001)."));
        }

        @Test
        void atLeastOneServiceOrDepartment() throws Exception {
            var user = data.user("Owner");
            var provider = flow.start(user, "provider");
            flow.business(provider, user, soleBusiness("Pipes Co", "x").replace("[\"x\"]", "[]"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("categories"))
                    .andExpect(jsonPath("$.errors[0].message").value("Pick at least one service."));
            var seller = flow.start(user, "seller");
            flow.business(seller, user, soleBusiness("Shop Co", "x").replace("[\"x\"]", "[]"))
                    .andExpect(jsonPath("$.errors[0].message").value("Pick at least one department."));
        }

        @ParameterizedTest(name = "[{index}] {0}: {1} categories → Maximum {2}.")
        @CsvSource({
            "kitchen, 4, 3",
            "seller, 6, 5",
            "provider, 11, 10",
            "both, 11, 10",
        })
        void categoryLimitPerType(String type, int count, int limit) throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, type);
            var root = switch (type) {
                case "kitchen" -> "food";
                case "seller" -> "shop";
                default -> "service";
            };
            var ids = jdbc.sql(
                            "select id from catalogue.categories where root = ? and parent_id is not null order by id limit ?")
                    .params(root, count)
                    .query(String.class)
                    .list();
            var json = soleBusiness("Busy Co", "x")
                    .replace(
                            "[\"x\"]",
                            ids.stream().map(i -> "\"" + i + "\"").toList().toString());
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("categories"))
                    .andExpect(jsonPath("$.errors[0].rule").value("range"))
                    .andExpect(jsonPath("$.errors[0].message").value("Maximum %d.".formatted(limit)));
        }

        @Test
        void categoriesMustComeFromTheTypeRoots() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "kitchen");
            flow.business(id, user, soleBusiness("Pho", "service.automotive.mobile-mechanic"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Pick categories from the list, or suggest a new one."));
        }

        @Test
        void legalDetailsFollowTheSchemaBranchOfTheStructure() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            var json = """
                    {"displayName":"Northwind","legalName":"Northwind Services Inc.","structure":"corp_ab",
                     "gstNumber":"123456789 RT0001",
                     "legalDetails":{"legal_corporate_name":"N","alberta_corporate_access_number":"12345",
                       "business_number":"12 34","incorporation_date":"2019-13-40","registered_office":"x",
                       "certificate_of_incorporation_doc":"01J9ZD3V000000000000000000"},
                     "principals":[{"legalName":"Dana Li","role":"director","ownershipPct":100}],
                     "categoryIds":["service.pets.dog-walker"]}""";
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("legalDetails.legal_corporate_name", "At least 2 characters."))
                    .andExpect(err(
                            "legalDetails.alberta_corporate_access_number", "Corporate access number is 10 digits."))
                    .andExpect(err("legalDetails.business_number", "Business number is 9 digits (e.g. 123456789)."))
                    .andExpect(err("legalDetails.incorporation_date", "Use the format YYYY-MM-DD."))
                    .andExpect(err("legalDetails.registered_office", "Enter the full address."))
                    .andExpect(err("legalDetails.certificate_of_incorporation_doc", "Upload the document."));
        }

        @Test
        void missingLegalFieldsAreRequired_andTradeNameNeedsItsRegistration() throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            var json = """
                    {"displayName":"Handy Co","legalName":"Sam Doe","structure":"sole",
                     "legalDetails":{"trade_name":"Handy Co","sin_collected_by_stripe":true},
                     "categoryIds":["service.home-trades.handyman"]}""";
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("legalDetails.owner_legal_name", "This is required."))
                    .andExpect(err("legalDetails.address", "This is required."))
                    .andExpect(err("legalDetails.trade_name_registration", "This is required."));
        }

        @ParameterizedTest(name = "[{index}] {2}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "[{\"legalName\":\"A B\",\"role\":\"partner_signing\",\"ownershipPct\":50}] | principals | Add at least 2 partners.",
                    "[{\"legalName\":\"A B\",\"role\":\"partner\",\"ownershipPct\":50},{\"legalName\":\"C D\",\"role\":\"partner\",\"ownershipPct\":50}] | principals | One partner must be the signing partner.",
                    "[{\"legalName\":\"A B\",\"role\":\"partner_signing\",\"ownershipPct\":70},{\"legalName\":\"C D\",\"role\":\"partner\",\"ownershipPct\":40}] | principals | Ownership can't add up to more than 100 %.",
                    "[{\"legalName\":\"\",\"role\":\"partner_signing\",\"ownershipPct\":50},{\"legalName\":\"C D\",\"role\":\"partner\",\"ownershipPct\":50}] | principals[0].legalName | Enter the full legal name.",
                    "[{\"legalName\":\"A B\",\"role\":\"partner_signing\",\"ownershipPct\":150},{\"legalName\":\"C D\",\"role\":\"partner\"}] | principals[0].ownershipPct | Enter a percentage from 0 to 100.",
                    "[{\"legalName\":\"A B\",\"role\":\"director\",\"ownershipPct\":50},{\"legalName\":\"C D\",\"role\":\"partner_signing\"}] | principals[0].role | Pick one of the roles listed.",
                })
        void partnershipPrincipals(String principals, String field, String message) throws Exception {
            var user = data.user("Owner");
            var id = flow.start(user, "provider");
            var json = """
                    {"displayName":"Two Hands","legalName":"Two Hands Partnership","structure":"partnership",
                     "legalDetails":{"partnership_name":"Two Hands","partnership_registration":"PR-1","business_number":"%s"},
                     "principals":%s,"categoryIds":["service.home-trades.handyman"]}""".formatted(randomBn(), principals);
            flow.business(id, user, json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err(field, message));
        }

        @Test
        void businessNumberIsUnique() throws Exception {
            var user = data.user("Owner");
            var bn = randomBn();
            var first = flow.start(user, "provider");
            flow.business(first, user, corpBusiness(flow.upload(first, user, "legal"), bn))
                    .andExpect(status().isOk());
            var second = flow.start(user, "provider");
            flow.business(second, user, corpBusiness(flow.upload(second, user, "legal"), bn))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err(
                            "legalDetails.business_number",
                            "That business number is already registered on Northline."));
        }

        @Test
        void otherMerchantsDocumentsDoNotCount() throws Exception {
            var user = data.user("Owner");
            var other = flow.start(user, "provider");
            var foreignDoc = flow.upload(other, user, "legal");
            var id = flow.start(user, "provider");
            flow.business(id, user, corpBusiness(foreignDoc, randomBn()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("legalDetails.certificate_of_incorporation_doc", "Upload the document."));
        }

        @Test
        void onlyTheOwnerEdits_andMembersOnly() throws Exception {
            var owner = data.user("Owner");
            var id = flow.start(owner, "provider");
            var tech = data.user("Tech");
            data.member(id, tech, MerchantRole.TECHNICIAN);

            flow.business(id, tech, soleBusiness("Pipes", "service.home-trades.plumber"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            flow.business(id, data.user("Stranger"), soleBusiness("Pipes", "service.home-trades.plumber"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get("/api/v1/merchants/{id}/onboarding", id).with(TestJwt.memberWithoutMfa(owner)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
            mvc.perform(get("/api/v1/merchants/{id}/onboarding", id).with(TestJwt.member(tech)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class VerificationStep {

        String user;
        String id;

        @BeforeEach
        void kitchen() throws Exception {
            user = data.user("Ravi");
            id = flow.start(user, "kitchen");
            flow.business(id, user, soleBusiness("Pho Aspen", "food.format.restaurant-dine-in-and-takeout"))
                    .andExpect(status().isOk());
        }

        String check(String key) throws Exception {
            List<String> ids =
                    JsonPath.read(flow.onboarding(id, user), "$.checklist[?(@.key == '%s')].id".formatted(key));
            return ids.getFirst();
        }

        @Test
        void instantChecksVerifyThroughTheFakes() throws Exception {
            flow.complete(id, user, check("kyc"), "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checklist[0].status").value("verified"))
                    .andExpect(jsonPath("$.checksComplete").value(1));
            flow.complete(id, user, check("bank"), "{}")
                    .andExpect(jsonPath("$.checklist[?(@.key == 'bank')].reference")
                            .value("TD ··3391"));
        }

        @Test
        void verifiedChecksCannotBeRedone() throws Exception {
            var kyc = check("kyc");
            flow.complete(id, user, kyc, "{}").andExpect(status().isOk());
            flow.complete(id, user, kyc, "{}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("already_verified"));
        }

        @ParameterizedTest(name = "[{index}] {0} {1} → {3}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "ahs_permit           | {}                            | reference  | Enter the permit number.",
                    "gst                  | {\"reference\":\"12\"}        | reference  | Format is 9 digits + RT0001 (e.g. 123456789 RT0001).",
                    "insurance            | {}                            | documentId | Upload the document.",
                    "allergen_attestation | {}                            | choice     | Read and sign to continue.",
                    "aglc                 | {}                            | reference  | Enter the licence number, or confirm you don't sell alcohol.",
                    "site_visit           | {\"reference\":\"tomorrow\"}  | reference  | Pick one of the offered visit slots.",
                    "site_visit           | {\"reference\":\"2020-01-01T10:00:00Z\"} | reference | Pick one of the offered visit slots.",
                    "inspection           | {\"documentId\":\"01J9ZD3V000000000000000000\"} | documentId | Upload the document.",
                })
        void evidenceIsValidated(String key, String body, String field, String message) throws Exception {
            flow.complete(id, user, check(key), body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err(field, message));
        }

        @Test
        void expiredDocumentsAreRejected() throws Exception {
            var doc = flow.upload(id, user, "verification");
            flow.complete(
                            id,
                            user,
                            check("insurance"),
                            "{\"documentId\":\"%s\",\"expiresOn\":\"2020-01-01\"}".formatted(doc))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("expiresOn", "This document has expired."));
        }

        @Test
        void uploadsAndSlotsWaitForAHuman_numbersMatchTheRegistry() throws Exception {
            var doc = flow.upload(id, user, "verification");
            flow.complete(
                            id,
                            user,
                            check("insurance"),
                            "{\"documentId\":\"%s\",\"expiresOn\":\"2099-05-31\"}".formatted(doc))
                    .andExpect(jsonPath("$.checklist[?(@.key == 'insurance')].status")
                            .value("submitted"))
                    .andExpect(jsonPath("$.checklist[?(@.key == 'insurance')].expiresOn")
                            .value("2099-05-31"))
                    .andExpect(jsonPath("$.checklist[?(@.key == 'insurance')].document.fileName")
                            .value("document.pdf"));
            flow.complete(id, user, check("ahs_permit"), "{\"reference\":\"FS-2024-88120\"}")
                    .andExpect(jsonPath("$.checklist[?(@.key == 'ahs_permit')].status")
                            .value("verified"))
                    .andExpect(jsonPath("$.checklist[?(@.key == 'ahs_permit')].reference")
                            .value("FS-2024-88120"));
            flow.complete(id, user, check("gst"), "{\"reference\":\"123456789rt0001\"}")
                    .andExpect(
                            jsonPath("$.checklist[?(@.key == 'gst')].reference").value("123456789 RT0001"));
            flow.complete(id, user, check("aglc"), "{\"choice\":\"not_applicable\"}")
                    .andExpect(jsonPath("$.checklist[?(@.key == 'aglc')].reference")
                            .value("not_applicable"));
        }

        @Test
        void submitNeedsEveryCheck_thenGoesPending() throws Exception {
            flow.submit(id, user)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("verifications", "Complete every check before submitting."));

            flow.completeAll(id, user);
            flow.submit(id, user)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("pending"))
                    .andExpect(jsonPath("$.step").value("review"))
                    .andExpect(jsonPath("$.submittedAt").exists())
                    .andExpect(jsonPath("$.checksComplete").value(12));

            assertThat(events.stream(MerchantSubmitted.class)).singleElement().satisfies(e -> {
                assertThat(e.aggregateId()).isEqualTo(id);
                assertThat(e.actorId()).isEqualTo(user);
                assertThat(e.merchantType()).isEqualTo("kitchen");
            });
            assertThat(jdbc.sql("select status from merchants.merchants where id = ?")
                            .param(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("pending");

            flow.submit(id, user)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("already_submitted"));
            flow.business(id, user, soleBusiness("Pho Aspen", "food.format.takeout-only"))
                    .andExpect(status().isConflict());
        }

        @Test
        void laterStepsNeedASubmittedApplication() throws Exception {
            mvc.perform(patch("/api/v1/merchants/{id}/onboarding", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"step\":\"page\"}")
                            .with(TestJwt.member(user)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_submitted"));

            flow.completeAll(id, user);
            flow.submit(id, user).andExpect(status().isOk());
            mvc.perform(patch("/api/v1/merchants/{id}/onboarding", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"step\":\"page\"}")
                            .with(TestJwt.member(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("page"));
        }

        @Test
        void submitBeforeTheBusinessStepIsRejected() throws Exception {
            var fresh = flow.start(user, "provider");
            flow.submit(fresh, user)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("business", "Complete the Business step first."));
        }

        @Test
        void devApprovalDoesNotExistOutsideTheLocalProfile() throws Exception {
            mvc.perform(post("/api/v1/dev/merchants/{id}/approve", id).with(TestJwt.member(user)))
                    .andExpect(status().isNotFound());
        }
    }

    static org.springframework.test.web.servlet.ResultMatcher err(String field, String message) {
        return jsonPath("$.errors[?(@.field == '%s')].message".formatted(field)).value(message);
    }
}
