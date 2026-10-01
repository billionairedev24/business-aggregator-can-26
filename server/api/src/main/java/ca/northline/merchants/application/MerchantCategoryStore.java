package ca.northline.merchants.application;

import ca.northline.merchants.api.MerchantCategories.Business;
import ca.northline.merchants.api.MerchantCategories.Limit;
import ca.northline.merchants.api.MerchantCategories.Usage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code merchants.category_limits} and the categories in {@code merchants.merchant_categories} (S-94). */
public interface MerchantCategoryStore {

    List<Limit> limits();

    Optional<Limit> limit(String merchantType);

    void setLimit(String merchantType, int max, String actorId, Instant at);

    Map<String, Usage> usage();

    /** Requested suggestions: suggestion id → its name (the first business's wording) and the businesses. */
    Map<String, Map.Entry<String, List<Business>>> suggestions();

    /** Businesses holding the suggestion, and of those the ones that already hold {@code categoryId}. */
    List<String> holders(String suggestionId);

    List<String> holding(List<String> merchantIds, String categoryId);

    /** Points the business's suggestion row at {@code categoryId} with {@code status}; drops its suggested name. */
    void move(String merchantId, String suggestionId, String categoryId, String status);

    void drop(String merchantId, String suggestionId);
}
