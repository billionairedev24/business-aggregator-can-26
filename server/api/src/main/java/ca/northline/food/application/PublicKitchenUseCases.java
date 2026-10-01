package ca.northline.food.application;

import ca.northline.food.application.PublicKitchenViews.Kitchens;
import ca.northline.food.application.PublicKitchenViews.Restaurant;
import org.jspecify.annotations.Nullable;

/** Inbound ports of the consumer food pages (S-57). {@code lat}/{@code lng} = the visitor's delivery point, if known. */
public interface PublicKitchenUseCases {

    Kitchens kitchens(String city, @Nullable Double lat, @Nullable Double lng);

    Restaurant restaurant(String slug, @Nullable Double lat, @Nullable Double lng);
}
