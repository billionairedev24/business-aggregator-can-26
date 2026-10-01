package ca.northline.food.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Whether kitchens take orders now, as the consumer site shows them (S-46 home "N open", S-57 food landing): opening
 * hours of the day in the kitchen's market time zone (holiday hours win), the Studio's pause, auto-pause after too many late orders, and a live
 * menu. A kitchen without settings or hours is closed.
 */
public interface KitchenAvailability {

    /** One entry per requested kitchen. */
    Map<String, KitchenStatus> now(Collection<Kitchen> kitchens);

    /** A kitchen and its province, whose market's time zone its hours are kept in ({@code null} = default market). */
    record Kitchen(String merchantId, @Nullable String province) {}

    /**
     * @param opensAt the next opening within a week while closed, else null
     * @param closesAt the end of the current opening range while open
     * @param paused closed only because the kitchen (or auto-pause) paused new orders
     * @param fulfilment {@code courier}, {@code pickup}, {@code meal_kits}, {@code scheduled} as the Studio set them
     * @param prepMin minutes the kitchen currently promises (default prep + busy bump)
     */
    record KitchenStatus(
            boolean open,
            @Nullable Instant opensAt,
            @Nullable Instant closesAt,
            boolean paused,
            List<String> fulfilment,
            int prepMin) {

        public KitchenStatus {
            fulfilment = List.copyOf(fulfilment);
        }

        public static final KitchenStatus CLOSED = new KitchenStatus(false, null, null, false, List.of(), 0);
    }
}
