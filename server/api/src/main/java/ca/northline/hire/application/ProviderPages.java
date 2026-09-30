package ca.northline.hire.application;

import ca.northline.hire.domain.ServiceKind;
import ca.northline.trust.api.PublicReviews.ReviewPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The public provider page (S-54, design 06 {@code provider}): what the storefront API ({@code GET
 * /api/v1/storefronts/{slug}}: sections in order, brand, tagline, announcement, verified facts) doesn't carry — trust
 * figures, the bookable services, the service area, the next free slot and the first reviews.
 */
public final class ProviderPages {
    private ProviderPages() {}

    public interface ViewProvider {
        ProviderPage provider(String slug, String lang);
    }

    public interface ListProviderReviews {
        ReviewPage reviews(String slug, int limit, int offset);
    }

    /**
     * @param kind how the business is booked (from the category of most of its services); visits when it has none
     * @param category the category most of its services are in (the page's "Mobile mechanic · {city}"), if any
     * @param vehicle that category is about vehicles
     * @param since when Northline approved the business
     */
    public record ProviderPage(
            String merchantId,
            String slug,
            String name,
            String tier,
            @Nullable String city,
            Instant since,
            List<String> verifiedFacts,
            double rating,
            int reviewCount,
            @Nullable Double onTimePct,
            @Nullable Double disputePct,
            @Nullable Double rebookPct,
            ServiceKind kind,
            @Nullable CategoryRef category,
            boolean vehicle,
            boolean quoteable,
            List<Service> services,
            List<String> zones,
            @Nullable Instant nextAvailable,
            ReviewPage reviews) {}

    public record CategoryRef(String id, String slug, Map<String, String> names) {}

    /**
     * @param pricingMode {@code fixed} | {@code hourly} | {@code quote}
     * @param categorySlug the leaf category's slug
     */
    public record Service(
            String id,
            String name,
            @Nullable String included,
            String pricingMode,
            @Nullable Long priceCents,
            int durationMin,
            boolean instantBook,
            @Nullable String categorySlug,
            ServiceKind kind) {}
}
