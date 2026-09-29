package ca.northline.food.application;

import ca.northline.food.domain.KitchenPromo;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code food.kitchen_settings}, {@code opening_hours}, {@code holiday_hours}, {@code kitchen_promos}. */
public interface KitchenSettingsStore {

    Optional<SettingsRow> settings(String merchantId);

    /** Inserts or replaces the row. */
    void save(SettingsRow settings);

    /** Weekday (1–7) → hours; missing weekdays have no row yet. */
    Map<Integer, DayRow> hours(String merchantId);

    void saveHours(String merchantId, List<DayRow> days);

    List<HolidayRow> holidays(String merchantId, LocalDate from);

    void insertHoliday(HolidayRow holiday, String actorId);

    boolean deleteHoliday(String merchantId, String holidayId);

    Map<KitchenPromo, Boolean> promos(String merchantId);

    void savePromo(String merchantId, KitchenPromo promo, boolean enabled, String actorId);

    @Builder(toBuilder = true)
    record SettingsRow(
            String merchantId,
            int defaultPrepMin,
            int maxOrdersPer15,
            int prepBumpMin,
            long largeOrderCents,
            int largeOrderAddMin,
            @Nullable Integer autoPauseLate,
            @Nullable Instant pausedUntil,
            @Nullable String pausedBy,
            List<String> fulfilment,
            @Nullable BigDecimal radiusKm,
            List<String> deliveryAreas,
            boolean groupOrders,
            int groupMax,
            int scheduledDays) {}

    record DayRow(
            int weekday,
            List<List<String>> ranges,
            @Nullable String note) {}

    record HolidayRow(
            String id,
            String merchantId,
            LocalDate day,
            List<List<String>> ranges,
            @Nullable String note) {}
}
