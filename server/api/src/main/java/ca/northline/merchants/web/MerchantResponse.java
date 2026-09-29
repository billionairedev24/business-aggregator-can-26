package ca.northline.merchants.web;

import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import org.jspecify.annotations.Nullable;

/** Studio header summary. Enums serialize as lower-case codes ({@code "provider"}, {@code "master"}). */
record MerchantResponse(
        String id,
        String displayName,
        MerchantType type,
        @Nullable MerchantTier tier,
        @Nullable String city,
        @Nullable MerchantStatus status) {}
