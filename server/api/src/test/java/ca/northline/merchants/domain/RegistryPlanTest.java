package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The registry lookups behind the {@code registry} row read the generic legal-detail keys and go to the adapter the
 * business's province has in the region model ({@link RegistryRoutes}); a province without one goes to an agent.
 */
class RegistryPlanTest {

    static final RegistryRoutes WITH_ADAPTER =
            new RegistryRoutes(RegistrySource.ALBERTA_CORPORATE_REGISTRY, null, Set.of());

    static MerchantApplication application(
            BusinessStructure structure, Province province, Map<String, Object> details) {
        return MerchantApplication.builder()
                .id("M1")
                .type(MerchantType.PROVIDER)
                .status(MerchantStatus.APPLICANT)
                .step(OnboardingStep.BUSINESS)
                .province(province)
                .createdBy("U1")
                .takeRateBps(MerchantApplication.STARTING_TAKE_RATE_BPS)
                .displayName("Aspen Wrench")
                .legalName("Aspen Wrench Ltd.")
                .structure(structure)
                .legalDetails(details)
                .principals(List.of())
                .categories(List.of())
                .profile(BusinessProfile.EMPTY)
                .languages(List.of())
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    @Test
    void aProvincialCorporation_isLookedUpByItsProvincialRegistryNumber_atItsProvincesAdapter() {
        var a = application(
                BusinessStructure.CORP_PROV, Province.AB, Map.of("provincial_registry_number", "2201456789"));
        assertThat(RegistryPlan.business(a, WITH_ADAPTER))
                .extracting(RegistryQuery::source, RegistryQuery::subject, RegistryQuery::number)
                .containsExactly(
                        tuple(RegistrySource.ALBERTA_CORPORATE_REGISTRY, RegistrySubject.CORPORATION, "2201456789"));
    }

    @Test
    void aProvinceWithoutAnAdapter_sendsItsRecordsToAnAgent() {
        var a = application(BusinessStructure.CORP_PROV, Province.ON, Map.of("provincial_registry_number", "1234567"));
        assertThat(RegistryPlan.business(a, RegistryRoutes.NONE))
                .extracting(RegistryQuery::source, RegistryQuery::number)
                .containsExactly(tuple(RegistrySource.MANUAL, "1234567"));
    }

    @Test
    void aFederalCorporation_isCheckedFederally_andItsExtraProvincialRegistrationInTheProvince() {
        var a = application(
                BusinessStructure.CORP_FED,
                Province.QC,
                Map.of("corporations_canada_number", "1234567", "extra_provincial_registration_number", "1171234567"));
        assertThat(RegistryPlan.business(a, RegistryRoutes.NONE))
                .extracting(RegistryQuery::source, RegistryQuery::subject, RegistryQuery::number)
                .containsExactly(
                        tuple(RegistrySource.CORPORATIONS_CANADA, RegistrySubject.CORPORATION, "1234567"),
                        tuple(RegistrySource.MANUAL, RegistrySubject.EXTRA_PROVINCIAL, "1171234567"));
    }

    @Test
    void anExtraProvincialCorporation_usesTheGenericRegistrationKey() {
        var a = application(
                BusinessStructure.CORP_EX, Province.BC, Map.of("extra_provincial_registration_number", "A1234567"));
        assertThat(RegistryPlan.business(a, WITH_ADAPTER))
                .extracting(RegistryQuery::subject, RegistryQuery::number)
                .containsExactly(tuple(RegistrySubject.EXTRA_PROVINCIAL, "A1234567"));
    }

    @Test
    void legacyKeys_readAsTheGenericOnes_reachThePlanToo() {
        var details = LegacyLegalDetails.upgrade(Map.of("alberta_corporate_access_number", "2201456789"));
        var a = application(BusinessStructure.of("corp_ab"), Province.AB, details);
        assertThat(a.getStructure()).isEqualTo(BusinessStructure.CORP_PROV);
        assertThat(RegistryPlan.business(a, WITH_ADAPTER))
                .extracting(RegistryQuery::number)
                .containsExactly("2201456789");
    }
}
