package ca.northline.food.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-67): the median price of dishes comparable to a kitchen's — live, approved dishes of the other
 * kitchens in its market that share one of its cuisines. Null when the kitchen has no public cuisine yet (it is
 * checked again when approved) or fewer than {@value #MIN_DISHES} dishes compare: too few to call a price an outlier.
 */
public interface PriceBenchmarks {

    int MIN_DISHES = 5;

    @Nullable
    Long median(String merchantId);
}
