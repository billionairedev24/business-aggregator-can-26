package ca.northline.merchants.web;

import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.security.MerchantRole;
import org.jspecify.annotations.Nullable;

/** One entry of the "Switch business" menu. */
record BusinessResponse(
        String id,
        String displayName,
        MerchantType type,
        @Nullable MerchantTier tier,
        @Nullable String city,
        @Nullable MerchantStatus status,
        MerchantRole role) {}
