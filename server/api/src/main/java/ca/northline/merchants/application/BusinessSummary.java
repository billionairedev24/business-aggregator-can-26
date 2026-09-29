package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.security.MerchantRole;
import org.jspecify.annotations.Nullable;

/** Read model: one business in a user's membership list. */
public record BusinessSummary(
        String merchantId,
        String displayName,
        MerchantType type,
        @Nullable MerchantTier tier,
        @Nullable String city,
        MerchantRole role) {}
