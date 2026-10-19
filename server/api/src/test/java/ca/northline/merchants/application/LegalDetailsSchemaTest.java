package ca.northline.merchants.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import ca.northline.merchants.application.LegalForm.Kind;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.region.api.LegalForms;
import ca.northline.region.api.LegalForms.Condition;
import ca.northline.region.api.LegalForms.FieldRule;
import ca.northline.region.api.LegalForms.Text;
import ca.northline.shared.RuleViolation.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * legal-details.schema.json, branch per structure, merged with a province's region rows: a valid example of each, the
 * typical failures, and what a province's overlay changes (labels, formats, required-ness). The region model's real
 * rows are covered by {@code LegalFormsApiTest}.
 */
class LegalDetailsSchemaTest {

    static final String DOC = "01J9ZD3V0000000000000D0C01";
    static final String XX = "XX";

    /** One made-up province: a 10-digit registry number, an attorney only for foreign corporations, no trade names. */
    static final LegalForms REGION = new LegalForms() {
        @Override
        public Optional<LegalForms.LegalForm> form(@Nullable String province, String structure) {
            return forms(province).stream()
                    .filter(f -> f.structure().equals(structure))
                    .findFirst();
        }

        @Override
        public List<LegalForms.LegalForm> forms(@Nullable String province) {
            if (!XX.equals(province)) {
                return List.of();
            }
            var number = new FieldRule(
                    new Text("Registry number", "Numéro au registre"),
                    new Text("On the certificate.", "Sur le certificat."),
                    "1234567890",
                    "^\\d{10}$",
                    new Text("The registry number is 10 digits.", "Le numéro compte 10 chiffres."),
                    null,
                    null);
            var attorney = new FieldRule(
                    new Text("Agent", "Mandataire"),
                    null,
                    null,
                    null,
                    null,
                    false,
                    new Condition("home_jurisdiction", List.of("US", "OTHER"), List.of()));
            return List.of(
                    row("sole", false, Map.of()),
                    row("corp_prov", true, Map.of("provincial_registry_number", number)),
                    row("corp_ex", true, Map.of("attorney_for_service", attorney)));
        }

        private LegalForms.LegalForm row(String structure, boolean tradeName, Map<String, FieldRule> fields) {
            return new LegalForms.LegalForm(
                    XX,
                    structure,
                    new Text("Registry of XX", "Registre de XX"),
                    "https://example.test/registry",
                    new Text("Companies Act (XX)", "Loi (XX)"),
                    tradeName,
                    fields,
                    "test",
                    false,
                    List.of("format"));
        }
    };

    final LegalDetailsSchema schema = new LegalDetailsSchema(REGION);

    List<Violation> check(BusinessStructure s, @Nullable String province, Map<String, Object> details) {
        var form = schema.form(s, province);
        return schema.validate(form, schema.normalize(form, details));
    }

    static Map<String, Object> corpEx(String home, Map<String, Object> attorney) {
        var out = new java.util.LinkedHashMap<String, Object>(Map.of(
                "legal_corporate_name", "Coast Co Ltd.",
                "home_jurisdiction", home,
                "home_registration_number", "BC1234",
                "extra_provincial_registration_number", "EP-2",
                "business_number", "123456789",
                "certificate_of_status_doc", DOC));
        if (!attorney.isEmpty()) {
            out.put("attorney_for_service", attorney);
        }
        return out;
    }

    @Test
    void validExamplesPass_withoutAProvince() {
        Map<BusinessStructure, Map<String, Object>> examples = Map.of(
                BusinessStructure.SOLE,
                        Map.of(
                                "owner_legal_name",
                                "Amara Okafor",
                                "sin_collected_by_stripe",
                                true,
                                "address",
                                "12 Main St"),
                BusinessStructure.PARTNERSHIP,
                        Map.of(
                                "partnership_name",
                                "Two Hands",
                                "partnership_registration",
                                "PR-1",
                                "business_number",
                                "123 456 789"),
                BusinessStructure.CORP_PROV,
                        Map.of(
                                "legal_corporate_name", "2201456 Ltd.",
                                "provincial_registry_number", "2201456789",
                                "business_number", "123456789",
                                "incorporation_date", "2019-04-02",
                                "registered_office", "1208 17 Ave SW",
                                "certificate_of_incorporation_doc", DOC),
                BusinessStructure.CORP_FED,
                        Map.of(
                                "legal_corporate_name", "Northwind Services Inc.",
                                "corporations_canada_number", "1234567",
                                "extra_provincial_registration_number", "EP-1",
                                "business_number", "123456789",
                                "registered_office", "1 Main St",
                                "certificate_of_incorporation_doc", DOC,
                                "annual_return_current", true),
                BusinessStructure.CORP_EX, corpEx("BC", Map.of("name", "Lee Law", "address_in_province", "2 Main St")),
                BusinessStructure.COOP,
                        Map.of(
                                "registered_name", "Prairie Growers Co-op",
                                "cooperative_registration", "CO-1",
                                "business_number", "123456789",
                                "board_resolution_doc", DOC,
                                "registered_office", "3 Main St"),
                BusinessStructure.NONPROFIT,
                        Map.of(
                                "registered_name", "Food Bank Society",
                                "society_registration", "SO-1",
                                "cra_charity_number", "123456789 rr0001",
                                "business_number", "123456789",
                                "board_resolution_doc", DOC,
                                "registered_office", "4 Main St"));
        examples.forEach((structure, details) ->
                assertThat(check(structure, null, details)).as(structure.code()).isEmpty());
    }

    @Test
    void fieldsOfAnotherStructureAreRejected_andNestedObjectsChecked() {
        var details = corpEx("Mars", Map.of("name", "Lee Law"));
        details.put("owner_legal_name", "Nobody");
        assertThat(check(BusinessStructure.CORP_EX, null, details))
                .extracting(Violation::field, Violation::message)
                .containsExactlyInAnyOrder(
                        tuple("legalDetails.home_jurisdiction", LegalDetailsSchema.OPTION),
                        tuple("legalDetails.attorney_for_service.address_in_province", LegalDetailsSchema.REQUIRED),
                        tuple("legalDetails.owner_legal_name", LegalDetailsSchema.UNKNOWN));
    }

    @Test
    void sinIsNeverStored_onlyTheStripeFlag() {
        assertThat(check(
                        BusinessStructure.SOLE,
                        null,
                        Map.of("owner_legal_name", "Sam Doe", "address", "12 Main St SW")))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.field()).isEqualTo("legalDetails.sin_collected_by_stripe");
                    assertThat(v.message()).isEqualTo(LegalDetailsSchema.SIN);
                });
    }

    @Test
    void theProvinceSetsTheRegistryNumbersLabelFormatAndMessage() {
        var form = schema.form(BusinessStructure.CORP_PROV, XX);
        assertThat(form.registry()).isEqualTo(new Text("Registry of XX", "Registre de XX"));
        assertThat(form.law()).isNotNull();
        assertThat(form.verified()).isFalse();
        var number = form.field("provincial_registry_number").orElseThrow();
        assertThat(number.label()).isEqualTo(new Text("Registry number", "Numéro au registre"));
        assertThat(number.pattern()).isEqualTo("^\\d{10}$");
        assertThat(number.compact()).isTrue();
        assertThat(number.required()).isTrue();

        var details = Map.<String, Object>of(
                "legal_corporate_name", "Some Co",
                "provincial_registry_number", "12 34",
                "business_number", "123456789",
                "incorporation_date", "2019-04-02",
                "registered_office", "1 Main St",
                "certificate_of_incorporation_doc", DOC);
        assertThat(check(BusinessStructure.CORP_PROV, XX, details))
                .extracting(Violation::field, Violation::message)
                .containsExactly(tuple("legalDetails.provincial_registry_number", "The registry number is 10 digits."));
        // the generic form has no format for the provincial number
        assertThat(check(BusinessStructure.CORP_PROV, null, details)).isEmpty();
    }

    @Test
    void theProvinceDecidesWhenTheAttorneyIsRequired() {
        assertThat(check(BusinessStructure.CORP_EX, XX, corpEx("BC", Map.of()))).isEmpty();
        assertThat(check(BusinessStructure.CORP_EX, XX, corpEx("US", Map.of())))
                .extracting(Violation::field)
                .containsExactly("legalDetails.attorney_for_service");
        // the schema's default: always required
        assertThat(check(BusinessStructure.CORP_EX, null, corpEx("BC", Map.of())))
                .extracting(Violation::field)
                .containsExactly("legalDetails.attorney_for_service");
    }

    @Test
    void aProvinceWithoutTradeNameRegistration_doesNotAskForIt() {
        var sole = Map.<String, Object>of(
                "owner_legal_name",
                "Sam Doe",
                "trade_name",
                "Handy Co",
                "sin_collected_by_stripe",
                true,
                "address",
                "1 Main St");
        assertThat(check(BusinessStructure.SOLE, XX, sole)).isEmpty();
        assertThat(schema.form(BusinessStructure.SOLE, XX).tradeNameRegistration())
                .isFalse();
        assertThat(check(BusinessStructure.SOLE, null, sole))
                .extracting(Violation::field)
                .containsExactly("legalDetails.trade_name_registration");
    }

    @Test
    void homeJurisdictionOffersEveryOtherProvince_andStillAcceptsOlderValues() {
        var home = schema.form(BusinessStructure.CORP_EX, "BC")
                .field("home_jurisdiction")
                .orElseThrow();
        assertThat(home.kind()).isEqualTo(Kind.CHOICE);
        assertThat(home.options()).contains("AB", "ON", "QC", "US", "OTHER").doesNotContain("BC", "OTHER_CA");
        assertThat(check(
                        BusinessStructure.CORP_EX,
                        "BC",
                        corpEx("OTHER_CA", Map.of("name", "A", "address_in_province", "2 Main St"))))
                .isEmpty();
    }

    @Test
    void oldAlbertaKeysAreReadAsTheGenericOnes() {
        var form = schema.form(BusinessStructure.CORP_EX, null);
        var details = schema.normalize(
                form,
                Map.of(
                        "structure", "corp_ab",
                        "alberta_extra_provincial_registration", "EP-9",
                        "attorney_for_service", Map.of("name", "Lee", "alberta_address", "2 Main St")));
        assertThat(details)
                .containsEntry("extra_provincial_registration_number", "EP-9")
                .containsEntry("attorney_for_service", Map.of("name", "Lee", "address_in_province", "2 Main St"))
                .containsEntry("structure", "corp_ex")
                .doesNotContainKeys("alberta_extra_provincial_registration");
    }

    @Test
    void everyStructureHasAForm_inTheSchemasOrder() {
        assertThat(schema.forms(XX))
                .extracting(ca.northline.merchants.application.LegalForm::structure)
                .containsExactly(BusinessStructure.values());
        assertThat(schema.documentFields(BusinessStructure.CORP_PROV))
                .containsExactly("certificate_of_incorporation_doc");
    }
}
