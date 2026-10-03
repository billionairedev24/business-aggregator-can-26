package ca.northline.region.api;

import org.jspecify.annotations.Nullable;

/**
 * S-120: whether a kitchen in a place is approved only after a passed kitchen visit — region data
 * ({@code region.regions.kitchen_visit}): the market's value, else its province's, else optional. Code never asks
 * "is this province X"; it asks here with the business's market or province.
 */
public interface KitchenVisitRules {

    /**
     * @param province the business's province code, or null
     * @param marketId the region market id (a pilot's market, or the business's city market), or null
     */
    boolean required(@Nullable String province, @Nullable String marketId);
}
