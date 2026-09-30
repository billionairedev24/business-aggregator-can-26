package ca.northline.search.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response bodies of the search API (contract: docs/runbooks/search.md § 8). */
final class SearchDtos {

    private SearchDtos() {}

    @Schema(description = "One page of results, with facets on the first page")
    record SearchResponse(
            List<ResultItem> items,

            @Schema(description = "Matching results, exact up to 10 000")
            long total,

            FacetsResponse facets,

            @Schema(description = "Pass as `after` for the next page; null on the last page") @Nullable
            String next) {}

    record ResultItem(
            String id,

            @Schema(allowableValues = {"service", "product", "food", "merchant"})
            String kind,

            String name,
            @Nullable String description,
            MerchantRef merchant,
            @Nullable CategoryRef category,
            @Nullable Long priceCents,

            @Schema(description = "fixed | quote | hourly (services); null = no price") @Nullable
            String pricingMode,

            @Nullable Double rating,
            int reviewCount,

            @Schema(allowableValues = {"registered", "trusted", "master"}) @Nullable
            String trustTier,

            @Schema(description = "From the given lat/lng, km, one decimal") @Nullable
            Double distanceKm,

            boolean instantBook,
            List<String> fulfilment,

            @Schema(description = "Inside its hours now (Edmonton), not paused, not sold out today")
            boolean openNow,

            @Schema(description = "A dish sold out today") boolean soldOut,

            @Schema(description = "Pooled delivery tonight: before the seller's cut-off, in stock")
            boolean onTonightsRun,

            @Nullable Integer prepMinutes,
            List<String> dietary,
            List<String> allergens,

            @Schema(description = "Opaque image reference: media:<id> or object:<key>") @Nullable
            String imageKey) {}

    record MerchantRef(
            String id,
            String name,
            String type,
            @Nullable String slug,
            @Nullable String tier) {}

    record CategoryRef(String id, @Nullable String name) {}

    record FacetsResponse(
            List<BucketResponse> kinds,
            List<BucketResponse> categories,
            List<BucketResponse> merchants,
            List<BucketResponse> tiers,

            @Schema(description = "under_10 | 10_25 | 25_50 | 50_100 | 100_plus (dollars)")
            List<BucketResponse> prices,

            List<BucketResponse> dietary) {}

    record BucketResponse(String value, @Nullable String label, long count) {}

    record SuggestResponse(List<SuggestionResponse> items) {}

    record SuggestionResponse(
            String text,

            @Schema(allowableValues = {"service", "product", "food", "merchant", "category"})
            String type,

            String id,
            @Nullable String merchantId,
            @Nullable String merchantName,
            @Nullable String merchantType,
            @Nullable String merchantSlug,
            @Nullable Long priceCents,
            @Nullable String trustTier,
            @Nullable Double rating,

            @Schema(description = "The typed part of `text` (UTF-16 offsets), for bold")
            List<HighlightResponse> highlight) {}

    record HighlightResponse(int start, int length) {}
}
