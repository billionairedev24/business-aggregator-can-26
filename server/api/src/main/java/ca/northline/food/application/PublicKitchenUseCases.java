package ca.northline.food.application;

import ca.northline.food.application.PublicKitchenViews.Kitchens;
import ca.northline.food.application.PublicKitchenViews.Restaurant;
import org.jspecify.annotations.Nullable;

/** Inbound ports of the consumer food pages (S-57). {@code lat}/{@code lng} = the visitor's delivery point, if known. */
public interface PublicKitchenUseCases {

    /**
     * @param city the visitor's city, or null when it isn't known (S-190): every live market's kitchens, nearest first
     *     when the point is known, else the best rated
     */
    Kitchens kitchens(@Nullable String city, @Nullable Double lat, @Nullable Double lng);

    Restaurant restaurant(String slug, @Nullable Double lat, @Nullable Double lng);
}
