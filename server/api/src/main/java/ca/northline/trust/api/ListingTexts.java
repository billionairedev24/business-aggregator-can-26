package ca.northline.trust.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-133: the listings submitted for vetting, with the text the trust &amp; safety screen looks at. Only the listing's own
 * words, category, price and the automated vetting result: no merchant name, contact or account data. Declared here and
 * implemented by the catalogue module, which already depends on {@code trust.api} (the other way round would be a
 * module cycle).
 */
public interface ListingTexts {

    /** Listings submitted after ({@code at}, {@code id}) in submission order, oldest first, at most {@code limit}. */
    List<ListingText> submittedAfter(Instant at, String id, int limit);

    /**
     * @param kind {@code service} or {@code product}
     * @param details the service's "what's included" or the product's description and bullets (may be empty)
     * @param categoryMedianCents the category's median price, null when unknown
     * @param vettingFlags the automated vetting flags' codes ({@code price_outlier}, …), empty when it passed
     */
    record ListingText(
            String listingId,
            String merchantId,
            String kind,
            Instant submittedAt,
            String name,
            String details,
            @Nullable String category,
            @Nullable Long priceCents,
            @Nullable Long categoryMedianCents,
            List<String> vettingFlags) {}
}
