package ca.northline.merchants.web;

import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import org.jspecify.annotations.Nullable;

/**
 * Studio header summary. Enums serialize as lower-case codes ({@code "provider"}, {@code "master"}). {@code role} is
 * the caller's team role and {@code teamCount} the team size (account menu "Team · N members"; settings workstream).
 */
record MerchantResponse(
        String id,
        String displayName,
        MerchantType type,
        @Nullable MerchantTier tier,
        @Nullable String city,
        @Nullable MerchantStatus status,
        @Nullable String role,
        @Nullable Integer teamCount) {

    MerchantResponse forMember(String memberRole, int members) {
        return new MerchantResponse(id, displayName, type, tier, city, status, memberRole, members);
    }
}
