package ca.northline.studio.application;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-75: the Studio page's "1,204 visits last 30 days · 8.6% booked" (design 02 storefront lede): visits to the public
 * page, bookings and orders made, over the last 30 days in the business's time zone (today included).
 */
public interface StorefrontStats {

    int DAYS = 30;

    Stats of(String merchantId);

    /**
     * @param booked bookings made plus orders placed in the same days
     * @param bookedRateBps booked ÷ visits in basis points (860 = 8.6%), null without visits
     * @param daily one entry per day, oldest first (zero-visit days included)
     */
    record Stats(long visits, long booked, @Nullable Long bookedRateBps, List<Day> daily) {
        public Stats {
            daily = List.copyOf(daily);
        }
    }

    record Day(LocalDate date, int visits) {}
}
