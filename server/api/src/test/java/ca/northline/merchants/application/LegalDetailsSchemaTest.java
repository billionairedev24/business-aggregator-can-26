package ca.northline.merchants.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.shared.RuleViolation.Violation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** legal-details.schema.json, branch per structure: a valid example of each and the typical failures. */
class LegalDetailsSchemaTest {

    static final String DOC = "01J9ZD3V0000000000000D0C01";
    final LegalDetailsSchema schema = new LegalDetailsSchema();

    Map<String, Object> normalized(BusinessStructure s, Map<String, Object> details) {
        return schema.normalize(s, details);
    }

    @Test
    void validExamplesPass() {
        Map<BusinessStructure, Map<String, Object>> examples = Map.of(
                BusinessStructure.SOLE,
                        Map.of(
                                "owner_legal_name",
                                "Amara Okafor",
                                "sin_collected_by_stripe",
                                true,
                                "address",
                                "12 Glenmore Trail SW"),
                BusinessStructure.PARTNERSHIP,
                        Map.of(
                                "partnership_name",
                                "Two Hands",
                                "partnership_registration",
                                "PR-1",
                                "business_number",
                                "123 456 789"),
                BusinessStructure.CORP_AB,
                        Map.of(
                                "legal_corporate_name", "2201456 Alberta Ltd.",
                                "alberta_corporate_access_number", "2201456789",
                                "business_number", "123456789",
                                "incorporation_date", "2019-04-02",
                                "registered_office", "1208 17 Ave SW, Calgary",
                                "certificate_of_incorporation_doc", DOC),
                BusinessStructure.CORP_FED,
                        Map.of(
                                "legal_corporate_name", "Northwind Services Inc.",
                                "corporations_canada_number", "1234567",
                                "alberta_extra_provincial_registration", "EP-1",
                                "business_number", "123456789",
                                "registered_office", "1 Main St, Calgary",
                                "certificate_of_incorporation_doc", DOC,
                                "annual_return_current", true),
                BusinessStructure.CORP_EX,
                        Map.of(
                                "legal_corporate_name", "Coast Co Ltd.",
                                "home_jurisdiction", "BC",
                                "home_registration_number", "BC1234",
                                "alberta_extra_provincial_registration", "EP-2",
                                "attorney_for_service",
                                        Map.of("name", "Lee Law", "alberta_address", "2 Main St, Calgary"),
                                "business_number", "123456789",
                                "certificate_of_status_doc", DOC),
                BusinessStructure.COOP,
                        Map.of(
                                "registered_name", "Prairie Growers Co-op",
                                "cooperative_registration", "CO-1",
                                "business_number", "123456789",
                                "board_resolution_doc", DOC,
                                "registered_office", "3 Main St, Red Deer"),
                BusinessStructure.NONPROFIT,
                        Map.of(
                                "registered_name", "Food Bank Society",
                                "society_registration", "SO-1",
                                "cra_charity_number", "123456789 rr0001",
                                "business_number", "123456789",
                                "board_resolution_doc", DOC,
                                "registered_office", "4 Main St, Calgary"));
        examples.forEach((structure, details) -> assertThat(schema.validate(structure, normalized(structure, details)))
                .as(structure.code())
                .isEmpty());
    }

    @Test
    void fieldsOfAnotherStructureAreRejected_andNestedObjectsChecked() {
        var problems = schema.validate(
                BusinessStructure.CORP_EX,
                normalized(
                        BusinessStructure.CORP_EX,
                        Map.of(
                                "legal_corporate_name", "Coast Co Ltd.",
                                "home_jurisdiction", "Mars",
                                "home_registration_number", "BC1234",
                                "alberta_extra_provincial_registration", "EP-2",
                                "attorney_for_service", Map.of("name", "Lee Law"),
                                "business_number", "123456789",
                                "certificate_of_status_doc", DOC,
                                "owner_legal_name", "Nobody")));
        assertThat(problems)
                .extracting(Violation::field, Violation::message)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "legalDetails.home_jurisdiction", LegalDetailsSchema.OPTION),
                        org.assertj.core.groups.Tuple.tuple(
                                "legalDetails.attorney_for_service.alberta_address", LegalDetailsSchema.REQUIRED),
                        org.assertj.core.groups.Tuple.tuple(
                                "legalDetails.owner_legal_name", LegalDetailsSchema.UNKNOWN));
    }

    @Test
    void sinIsNeverStored_onlyTheStripeFlag() {
        var problems = schema.validate(
                BusinessStructure.SOLE,
                normalized(BusinessStructure.SOLE, Map.of("owner_legal_name", "Sam Doe", "address", "12 Main St SW")));
        assertThat(problems).singleElement().satisfies(v -> {
            assertThat(v.field()).isEqualTo("legalDetails.sin_collected_by_stripe");
            assertThat(v.message()).isEqualTo(LegalDetailsSchema.SIN);
        });
    }
}
