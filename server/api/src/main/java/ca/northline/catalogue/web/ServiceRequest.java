package ca.northline.catalogue.web;

import static ca.northline.catalogue.domain.ListingMessages.*;

import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ServiceDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /services} and {@code PUT /services/{id}} (contract: {@code name, categoryId?, pricingMode, priceCents?,
 * durationMin, bufferMin, included, instantBook}; {@code sku} optional — generated from the name when absent).
 */
record ServiceRequest(
        @NotBlank(message = NAME_REQUIRED) @Size(max = NAME_MAX, message = NAME_TOO_LONG)
        String name,

        @Nullable String categoryId,
        @NotNull(message = PRICING_REQUIRED) PricingMode pricingMode,

        @Nullable @Positive(message = PRICE_POSITIVE) @Max(value = PRICE_MAX_CENTS, message = PRICE_POSITIVE)
        Long priceCents,

        @NotNull(message = DURATION_REQUIRED)
        @Min(value = 15, message = DURATION_RANGE)
        @Max(value = 720, message = DURATION_RANGE)
        Integer durationMin,

        @Nullable @Min(value = 0, message = BUFFER_RANGE) @Max(value = 120, message = BUFFER_RANGE)
        Integer bufferMin,

        @Nullable @Size(max = INCLUDED_MAX, message = INCLUDED_TOO_LONG)
        String included,

        @Nullable Boolean instantBook,

        @Nullable @Size(max = SKU_MAX, message = SKU_TOO_LONG)
        String sku) {

    ServiceDetails toDetails() {
        return new ServiceDetails(
                name,
                categoryId == null || categoryId.isBlank() ? null : categoryId,
                pricingMode,
                priceCents,
                durationMin,
                bufferMin == null ? 0 : bufferMin,
                included,
                !Boolean.FALSE.equals(instantBook),
                sku);
    }
}
