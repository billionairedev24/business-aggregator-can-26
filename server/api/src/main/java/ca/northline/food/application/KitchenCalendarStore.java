package ca.northline.food.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Outbound port: what decides whether kitchens are open (settings, hours, holidays, late tickets, a live menu). */
public interface KitchenCalendarStore {

    /** Kitchens without settings are absent. Holidays from {@code from} for a week; late = cooking past ready-by at {@code now}. */
    List<CalendarRow> calendars(Collection<String> merchantIds, LocalDate from, Instant now);

    /**
     * @param week ranges per ISO weekday as stored ({@code [["11:00","21:00"]]})
     * @param menuLive a live menu with at least one approved, published dish
     * @param radiusKm the delivery radius the kitchen set (Hours › Fulfilment), or null
     * @param avgItemCents the average price of its orderable dishes (the "24637" price level)
     */
    record CalendarRow(
            String merchantId,
            int defaultPrepMin,
            int prepBumpMin,
            @Nullable Integer autoPauseLate,
            @Nullable Instant pausedUntil,
            List<String> fulfilment,
            Map<Integer, List<List<String>>> week,
            Map<LocalDate, List<List<String>>> holidays,
            int lateOrders,
            boolean menuLive,
            @Nullable Double radiusKm,
            long avgItemCents,
            int scheduledDays) {}
}
