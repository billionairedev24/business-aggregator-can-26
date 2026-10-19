package ca.northline.merchants;

import static ca.northline.merchants.OnboardingApiTest.err;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.application.LegalDetailsSchema;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.region.api.LegalForms;
import ca.northline.region.api.Regions;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Region-neutral legal details (2026-10-19): the Business step's form comes from the schema plus the business's
 * province's rows in {@code region.legal_forms} (V372), the api serves it and validates against it, rows written with
 * the old Alberta-only keys still read, and registry lookups go to the province's adapter (or an agent).
 */
class LegalFormsApiTest extends IntegrationTest {

    static final List<String> OPENED = List.of("BC", "ON", "QC", "NL");
    static final String PROVINCES = "AB,BC,MB,NB,NL,NS,NT,NU,ON,PE,QC,SK,YT";

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Regions regions;

    @Autowired
    LegalForms legalForms;

    @Autowired
    LegalDetailsSchema schema;

    OnboardingFlow flow;
    String user;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        jdbc.sql("update region.regions set stage = 'waitlist' where kind = 'province' and province in (:p)")
                .param("p", OPENED)
                .update();
        regions.refresh();
        flow = new OnboardingFlow(mvc);
        user = data.user("Owner");
    }

    @AfterEach
    void close() {
        jdbc.sql("update region.regions set stage = 'off' where kind = 'province' and province in (:p)")
                .param("p", OPENED)
                .update();
        regions.refresh();
    }

    ResultActions forms(String merchantId, String userId) throws Exception {
        return mvc.perform(
                get("/api/v1/merchants/{id}/onboarding/legal-forms", merchantId).with(TestJwt.member(userId)));
    }

    static String form(String structure) {
        return "$.items[?(@.structure == '%s')]".formatted(structure);
    }

    static String field(String structure, String key) {
        return form(structure) + ".fields[?(@.key == '%s')]".formatted(key);
    }

    static String corpProv(String number) {
        return """
                {"displayName":"Northwind","legalName":"Northwind Services Inc.","structure":"corp_prov",
                 "gstNumber":"123456789 RT0001",
                 "legalDetails":{"legal_corporate_name":"Northwind Services Inc.","provincial_registry_number":"%s",
                   "business_number":"%s","incorporation_date":"2019-04-02","registered_office":"1 Main St, Suite 100",
                   "certificate_of_incorporation_doc":"%%s"},
                 "principals":[{"legalName":"Dana Li","role":"director","ownershipPct":100}],
                 "categoryIds":["service.pets.dog-walker"]}""".formatted(number, OnboardingFlow.randomBn());
    }

    String applicant(String province, String type) throws Exception {
        return flow.start(user, type, province);
    }

    // ------------------------------------------------------------------------------------------------ region data

    @Test
    void everyProvinceAndTerritoryHasEveryStructure_andItsOverlaysFitTheSchema() {
        for (var province : PROVINCES.split(",")) {
            var rows = legalForms.forms(province);
            assertThat(rows)
                    .as(province)
                    .extracting(LegalForms.LegalForm::structure)
                    .containsExactly(Arrays.stream(BusinessStructure.values())
                            .map(BusinessStructure::code)
                            .toArray(String[]::new));
            for (var row : rows) {
                var form = schema.form(BusinessStructure.of(row.structure()), province);
                assertThat(row.registry().en()).as(province).isNotBlank();
                assertThat(row.registry().fr()).as(province).isNotBlank();
                assertThat(row.law().fr()).as(province).isNotBlank();
                assertThat(row.verified())
                        .as(province + " " + row.structure())
                        .isEqualTo(row.unverified().isEmpty());
                row.fields().forEach((key, rule) -> {
                    assertThat(form.field(key))
                            .as(province + " " + row.structure() + " " + key)
                            .isPresent();
                    if (rule.label() != null) {
                        assertThat(rule.label().fr()).isNotBlank();
                    }
                    if (rule.pattern() != null) {
                        Pattern.compile(rule.pattern());
                        assertThat(rule.patternMessage()).as(key).isNotNull();
                        assertThat(rule.example()).as(key).matches(rule.pattern());
                        assertThat(row.unverified())
                                .as("a pattern is only set where the format is verified")
                                .doesNotContain("format");
                    }
                });
            }
        }
    }

    @Test
    void noProvinceNameIsWrittenInTheSchema() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(LegalDetailsSchema.RESOURCE)) {
            var text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text)
                    .doesNotContainIgnoringCase("alberta")
                    .doesNotContain("corp_ab")
                    .doesNotContain("Ontario");
        }
    }

    // ------------------------------------------------------------------------------------------------ the definition

    @Test
    void alberta_servesItsCorporateAccessNumber() throws Exception {
        var id = applicant("AB", "provider");
        forms(id, user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.province").value("AB"))
                .andExpect(jsonPath("$.items", hasSize(7)))
                .andExpect(jsonPath(form("corp_prov") + ".registry.en").value("Alberta Corporate Registry"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".label.en")
                        .value("Corporate access number"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".pattern")
                        .value("^\\d{9,10}$"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".required")
                        .value(true))
                .andExpect(jsonPath(field("corp_prov", "business_number") + ".pattern")
                        .value("^\\d{9}$"))
                .andExpect(jsonPath(field("corp_ex", "attorney_for_service") + ".label.en")
                        .value("Agent for service in Alberta"))
                .andExpect(jsonPath(
                        field("corp_ex", "attorney_for_service") + ".parts[*].key", hasItem("address_in_province")))
                .andExpect(jsonPath(field("corp_ex", "home_jurisdiction") + ".options[*]", not(hasItem("AB"))))
                .andExpect(jsonPath(form("sole") + ".tradeNameRegistration").value(true));
    }

    @Test
    void ontario_servesTheOcn_andAnAgentOnlyForCorporationsFromOutsideCanada() throws Exception {
        var id = applicant("ON", "provider");
        forms(id, user)
                .andExpect(status().isOk())
                .andExpect(jsonPath(form("corp_prov") + ".registry.en").value("Ontario Business Registry"))
                .andExpect(jsonPath(form("corp_prov") + ".registry.fr").value("Registre des entreprises de l'Ontario"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".label.en")
                        .value("Ontario Corporation Number (OCN)"))
                .andExpect(
                        jsonPath(field("corp_prov", "provincial_registry_number") + ".pattern", contains(nullValue())))
                .andExpect(jsonPath(field("sole", "trade_name_registration") + ".label.en")
                        .value("Business Identification Number (BIN)"))
                .andExpect(jsonPath(field("corp_ex", "attorney_for_service") + ".required")
                        .value(false))
                .andExpect(jsonPath(field("corp_ex", "attorney_for_service") + ".requiredWhen.in[*]", hasItem("US")))
                .andExpect(jsonPath(field("corp_ex", "home_jurisdiction") + ".options[*]", hasItem("AB")));
    }

    @Test
    void quebec_andBritishColumbia_serveTheirNumbersAndFormats() throws Exception {
        forms(applicant("QC", "provider"), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath(form("corp_prov") + ".registry.fr").value("Registraire des entreprises du Québec"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".label.fr")
                        .value("Numéro d'entreprise du Québec (NEQ)"))
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".pattern")
                        .value("^\\d{10}$"));
        forms(applicant("BC", "provider"), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath(field("corp_prov", "provincial_registry_number") + ".label.en")
                        .value("Incorporation number"))
                .andExpect(jsonPath(field("sole", "trade_name_registration") + ".pattern")
                        .value("^FM\\d{7}$"));
    }

    @Test
    void onlyMembersReadTheForms_withASecondFactor() throws Exception {
        var id = applicant("AB", "provider");
        forms(id, data.user("Stranger"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding/legal-forms", id).with(TestJwt.memberWithoutMfa(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        var tech = data.user("Tech");
        data.member(id, tech, MerchantRole.TECHNICIAN);
        forms(id, tech).andExpect(status().isOk());
    }

    /**
     * The Studio's tests and screenshots use what the api serves for four provinces
     * (web/apps/studio/src/test/legal-forms.json); {@code LEGAL_FORMS_FIXTURES_WRITE=1} rewrites the file.
     */
    @Test
    void theStudioFixturesAreWhatTheApiServes() throws Exception {
        var file = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("../../web/apps/studio/src/test/legal-forms.json")
                .normalize();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var served = json.createObjectNode();
        for (var province : List.of("AB", "BC", "ON", "QC")) {
            var body = forms(applicant(province, "provider"), user)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            served.set(province, json.readTree(body));
        }
        if ("1".equals(System.getenv("LEGAL_FORMS_FIXTURES_WRITE"))) {
            java.nio.file.Files.writeString(
                    file, json.writerWithDefaultPrettyPrinter().writeValueAsString(served) + "\n");
        }
        assertThat(json.readTree(java.nio.file.Files.readString(file)))
                .as("web/apps/studio/src/test/legal-forms.json — rerun with LEGAL_FORMS_FIXTURES_WRITE=1")
                .isEqualTo(served);
    }

    // ------------------------------------------------------------------------------------------------ validation

    @Test
    void eachProvincesFormatIsCheckedWithItsMessage() throws Exception {
        var ab = applicant("AB", "provider");
        flow.business(ab, user, corpProv("12345").formatted(flow.upload(ab, user, "legal")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        err("legalDetails.provincial_registry_number", "Corporate access number is 9 or 10 digits."));
        var qc = applicant("QC", "provider");
        flow.business(qc, user, corpProv("123").formatted(flow.upload(qc, user, "legal")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("legalDetails.provincial_registry_number", "The NEQ is 10 digits."));
        // Ontario has no verified format: any number is taken (an agent checks it)
        var on = applicant("ON", "provider");
        flow.business(on, user, corpProv("1234567").formatted(flow.upload(on, user, "legal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.structure").value("corp_prov"))
                .andExpect(jsonPath("$.business.legalDetails.provincial_registry_number")
                        .value("1234567"));
    }

    @Test
    void theTradeNameRegistrationIsRequiredWhereTheProvinceRegistersTradeNames() throws Exception {
        var sole = """
                {"displayName":"Handy Co","legalName":"Sam Doe","structure":"sole",
                 "legalDetails":{"owner_legal_name":"Sam Doe","trade_name":"Handy Co","sin_collected_by_stripe":true,
                   "address":"12 Main St, Unit 4"%s},
                 "categoryIds":["service.home-trades.handyman"]}""";
        flow.business(applicant("BC", "provider"), user, sole.formatted(""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("legalDetails.trade_name_registration", "This is required."));
        flow.business(applicant("BC", "provider"), user, sole.formatted(",\"trade_name_registration\":\"FM12\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err(
                        "legalDetails.trade_name_registration",
                        "BC registration number is FM + 7 digits (e.g. FM1016176)."));
        flow.business(applicant("BC", "provider"), user, sole.formatted(",\"trade_name_registration\":\"fm 1016176\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.legalDetails.trade_name_registration")
                        .value("FM1016176"));
        // no business-name registry there
        flow.business(applicant("NL", "provider"), user, sole.formatted("")).andExpect(status().isOk());
    }

    @Test
    void theAttorneyIsRequiredAsTheProvinceSays() throws Exception {
        var corpEx = """
                {"displayName":"Coast Co","legalName":"Coast Co Ltd.","structure":"corp_ex","gstNumber":"123456789 RT0001",
                 "legalDetails":{"legal_corporate_name":"Coast Co Ltd.","home_jurisdiction":"%s","home_registration_number":"X1",
                   "extra_provincial_registration_number":"1000123","business_number":"%s","certificate_of_status_doc":"%s"},
                 "principals":[{"legalName":"Dana Li","role":"director","ownershipPct":100}],
                 "categoryIds":["service.pets.dog-walker"]}""";
        var on = applicant("ON", "provider");
        var doc = flow.upload(on, user, "legal");
        flow.business(on, user, corpEx.formatted("US", OnboardingFlow.randomBn(), doc))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("legalDetails.attorney_for_service", "This is required."));
        flow.business(on, user, corpEx.formatted("BC", OnboardingFlow.randomBn(), doc))
                .andExpect(status().isOk());
        var ab = applicant("AB", "provider");
        flow.business(ab, user, corpEx.formatted("BC", OnboardingFlow.randomBn(), flow.upload(ab, user, "legal")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("legalDetails.attorney_for_service", "This is required."));
    }

    // ------------------------------------------------------------------------------------------------ old keys

    @Test
    void oldClientsSendingTheAlbertaKeys_areSavedUnderTheGenericOnes() throws Exception {
        var id = applicant("AB", "provider");
        var doc = flow.upload(id, user, "legal");
        var old = OnboardingFlow.corpBusiness(doc, OnboardingFlow.randomBn())
                .replace("\"structure\":\"corp_prov\"", "\"structure\":\"corp_ab\"")
                .replace("provincial_registry_number", "alberta_corporate_access_number");
        flow.business(id, user, old)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.structure").value("corp_prov"))
                .andExpect(jsonPath("$.business.legalDetails.provincial_registry_number")
                        .value("2201456789"))
                .andExpect(jsonPath("$.business.legalDetails.alberta_corporate_access_number")
                        .doesNotExist());
        assertThat(jdbc.sql("select structure from merchants.merchants where id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("corp_prov");
    }

    @Test
    void rowsStillHoldingTheOldCodeAndKeys_readAsTheGenericOnes() throws Exception {
        var id = applicant("AB", "provider");
        var doc = flow.upload(id, user, "legal");
        flow.business(id, user, OnboardingFlow.corpBusiness(doc, OnboardingFlow.randomBn()))
                .andExpect(status().isOk());
        // what an api of the previous release writes while this one deploys (V373 rewrote the rows before it)
        jdbc.sql("""
                        update merchants.merchants
                           set structure = 'corp_ab',
                               legal_details = (legal_details - 'provincial_registry_number')
                                 || '{"structure":"corp_ab","alberta_corporate_access_number":"2201456789"}'::jsonb
                         where id = ?""").params(id).update();
        var body = flow.onboarding(id, user);
        assertThat((String) JsonPath.read(body, "$.business.structure")).isEqualTo("corp_prov");
        Map<String, Object> details = JsonPath.read(body, "$.business.legalDetails");
        assertThat(details)
                .containsEntry("provincial_registry_number", "2201456789")
                .containsEntry("structure", "corp_prov")
                .doesNotContainKey("alberta_corporate_access_number");
    }

    // ------------------------------------------------------------------------------------------------ registry plan

    @Test
    void registryLookupsGoToTheProvincesAdapter_orToAnAgent() throws Exception {
        var ab = applicant("AB", "provider");
        flow.business(ab, user, corpProv("2201456789").formatted(flow.upload(ab, user, "legal")))
                .andExpect(status().isOk());
        lookUpRegistry(ab);
        assertThat(source(ab)).isEqualTo("alberta_corporate_registry");

        var on = applicant("ON", "provider");
        flow.business(on, user, corpProv("1234567").formatted(flow.upload(on, user, "legal")))
                .andExpect(status().isOk());
        lookUpRegistry(on);
        assertThat(source(on)).isEqualTo("manual");
    }

    void lookUpRegistry(String merchantId) throws Exception {
        List<String> ids = JsonPath.read(flow.onboarding(merchantId, user), "$.checklist[?(@.key == 'registry')].id");
        flow.complete(merchantId, user, ids.getFirst(), "{}").andExpect(status().isOk());
    }

    String source(String merchantId) {
        return jdbc.sql(
                        "select source from merchants.registry_checks where merchant_id = ? order by checked_at desc limit 1")
                .params(merchantId)
                .query(String.class)
                .single();
    }
}
