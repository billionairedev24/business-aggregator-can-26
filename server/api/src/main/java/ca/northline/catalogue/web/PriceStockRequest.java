package ca.northline.catalogue.web;

import static ca.northline.catalogue.domain.ListingMessages.PRICE_MAX_CENTS;
import static ca.northline.catalogue.domain.ListingMessages.PRICE_POSITIVE;
import static ca.northline.catalogue.domain.ListingMessages.STOCK_NEGATIVE;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;

/** {@code PATCH …/listings/{listingId}/price-stock} (S-127): either field may be left out to keep its value. */
record PriceStockRequest(
        @Schema(description = "New price in cents (CAD), tax excluded. Leave out to keep the current price.")
        @Nullable
        @Positive(message = PRICE_POSITIVE)
        @Max(value = PRICE_MAX_CENTS, message = PRICE_POSITIVE)
        Long priceCents,

        @Schema(description = "Units in stock (products only). Leave out to keep the current stock.")
        @Nullable
        @PositiveOrZero(message = STOCK_NEGATIVE)
        Integer stock) {}
