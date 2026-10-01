package ca.northline.catalogue.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Bookable services customers can see (S-53 … S-56): live and approved rows of {@code catalogue.services}. Whether the
 * business itself may trade is the caller's check (merchants module). Names are resolved for {@code lang} with
 * English fallback.
 */
public interface ServiceOffers {

    /**
     * @param categoryId the leaf category ({@code service.automotive.mobile-mechanic})
     * @param pricingMode {@code fixed} | {@code hourly} | {@code quote}
     * @param priceCents the fixed price, or the hourly rate; null for quote-only services
     * @param included "What's included", as the business wrote it
     * @param sales30d bookings in the last 30 days (S-38), for "common jobs"
     */
    record Offer(
            String serviceId,
            String merchantId,
            @Nullable String categoryId,
            String name,
            @Nullable String included,
            String pricingMode,
            @Nullable Long priceCents,
            int durationMin,
            int bufferMin,
            boolean instantBook,
            int sales30d) {

        public boolean quoteOnly() {
            return "quote".equals(pricingMode) || priceCents == null;
        }
    }

    /** Services in any of the leaf categories, most booked first. */
    List<Offer> inCategories(Collection<String> categoryIds, String lang);

    /** One business's services, most booked first. */
    List<Offer> ofMerchant(String merchantId, String lang);

    Optional<Offer> find(String serviceId, String lang);
}
