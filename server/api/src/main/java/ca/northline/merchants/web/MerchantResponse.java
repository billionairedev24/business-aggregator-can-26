package ca.northline.merchants.web;

import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.region.api.FrenchListings;
import org.jspecify.annotations.Nullable;

/**
 * Studio header summary. Enums serialize as lower-case codes ({@code "provider"}, {@code "master"}). {@code role} is
 * the caller's team role and {@code teamCount} the team size (account menu "Team · N members"; settings workstream).
 * {@code region}: where the business is in the region model (S-134) — the Studio shows its dates in {@code timeZone}
 * and fills place names in its copy from {@code provinceName}.
 */
record MerchantResponse(
        String id,
        String displayName,
        MerchantType type,
        @Nullable MerchantTier tier,
        @Nullable String city,
        @Nullable MerchantStatus status,
        @Nullable String role,
        @Nullable Integer teamCount,
        @Nullable Region region) {

    /**
     * @param province the business's province (else the configured default province), null when neither is known
     * @param provinceIn "in Alberta" / "en Alberta", {@code provinceOf} "Alberta" / "de l'Alberta": the name as copy
     *     needs it in each language
     * @param privacyLaw the province's privacy law code ({@code pipeda}, {@code ab_pipa}, {@code bc_pipa},
     *     {@code qc_law25})
     * @param frenchFirst the place is French-first (S-116, region configuration): the Studio opens in French unless the
     *     person chose English, and {@code frenchListings} says what listings there need in French
     */
    record Region(
            @Nullable String province,
            Names provinceName,
            Names provinceIn,
            Names provinceOf,
            String timeZone,
            @Nullable String privacyLaw,
            boolean frenchFirst,
            FrenchListings frenchListings) {}

    record Names(String en, String fr) {}

    MerchantResponse forMember(String memberRole, int members, Region place) {
        return new MerchantResponse(id, displayName, type, tier, city, status, memberRole, members, place);
    }
}
