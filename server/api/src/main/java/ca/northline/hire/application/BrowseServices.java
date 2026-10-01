package ca.northline.hire.application;

import ca.northline.availability.api.ServiceAreas.Place;
import ca.northline.hire.domain.ServiceKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Services landing, service category and provider list (S-53, design 06 {@code services}, {@code svcCategory}, {@code providers}). */
public final class BrowseServices {
    private BrowseServices() {}

    /** Every service group with its categories; {@code live} = categories with at least one bookable provider. */
    public interface ListCategories {
        Landing landing(String lang);
    }

    /** One category: what it is, its common jobs and typical prices. */
    public interface ViewCategory {
        Category category(String slug, String lang);
    }

    /** The category's providers whose service area covers the customer, most trusted first. */
    public interface ListProviders {
        Providers providers(String slug, Place place, String lang);
    }

    /** @param provinces where the live providers are (province codes, sorted) */
    public record Landing(int liveCategories, int providers, List<String> provinces, List<Group> groups) {}

    /** @param key the group slug ({@code automotive}); the web app words its line from it */
    public record Group(
            String id,
            String key,
            Map<String, String> names,
            @Nullable String note,
            List<Item> items) {}

    /** @param names {@code en} always, {@code fr} once the taxonomy is translated */
    public record Item(String slug, Map<String, String> names, ServiceKind kind, int providers) {}

    /**
     * @param vehicle jobs are about a vehicle (automotive): the booking wizard asks for it
     * @param quoteable customers can ask for quotes
     */
    public record Category(
            String id,
            String slug,
            Map<String, String> names,
            Group group,
            ServiceKind kind,
            boolean vehicle,
            @Nullable String regulatedRegistry,
            int providers,
            List<String> provinces,
            boolean quoteable,
            List<Job> jobs) {}

    /**
     * A common job with its typical price: the lowest price any provider asks for a service of that name.
     *
     * @param pricingMode {@code fixed} | {@code hourly} | {@code quote}
     */
    public record Job(
            String name,
            @Nullable String included,
            String pricingMode,
            @Nullable Long priceCents,
            int durationMin) {}

    /**
     * @param area the zone the customer is in ("Beltline"), null when unknown
     * @param city the city the list is for
     */
    public record Providers(
            String categorySlug,
            ServiceKind kind,
            @Nullable String area,
            @Nullable String city,
            List<ProviderCard> items) {}

    /**
     * @param onTimePct on-time arrival (percent), null before the first nightly score
     * @param disputePct dispute rate (percent)
     * @param rebookPct re-book rate (percent)
     * @param fromCents the lowest price in the category (hourly rate for hourly work), null when everything is quoted
     * @param nextAvailable the earliest free start for the shortest service in the category
     */
    public record ProviderCard(
            String merchantId,
            String slug,
            String name,
            String tier,
            String brandColor,
            @Nullable String blurb,
            double rating,
            int reviewCount,
            @Nullable Double onTimePct,
            @Nullable Double disputePct,
            @Nullable Double rebookPct,
            @Nullable Long fromCents,
            String pricingMode,
            boolean instantBook,
            @Nullable Instant nextAvailable,
            List<String> zones) {}
}
