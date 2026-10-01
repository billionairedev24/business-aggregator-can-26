package ca.northline.search.application;

import ca.northline.search.domain.Highlight;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One completion under the search box (design 06: products, services, dishes, shops/providers, categories).
 *
 * @param type {@code service} | {@code product} | {@code food} | {@code merchant} | {@code category}
 * @param id the listing or merchant id, or the category id
 * @param highlight the typed part of {@code text}
 */
public record Suggestion(
        String text,
        String type,
        String id,
        @Nullable String merchantId,
        @Nullable String merchantName,
        @Nullable String merchantType,
        @Nullable String merchantSlug,
        @Nullable Long priceCents,
        @Nullable String trustTier,
        @Nullable Double rating,
        List<Highlight> highlight) {}
