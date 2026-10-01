package ca.northline.food.application;

import ca.northline.food.application.KitchenMerchantFacts.FoodSafety;
import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.MenuStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Live orders (KDS) and Hours, prep &amp; capacity. */
public final class KitchenUseCases {
    private KitchenUseCases() {}

    // ── Kitchen businesses only ─────────────────────────────────────────────────

    /**
     * S-73: the kitchen screens' endpoints exist only for a kitchen business. Any other type (provider, seller, both)
     * gets 404 {@code not_found} — there is no kitchen to show — after the usual membership checks (403 first).
     */
    public interface RequireKitchen {
        void require(String merchantId);
    }

    // ── Live orders ───────────────────────────────────────────────────────────

    /** The kitchen display: open orders, Accept → Cooking → Ready → handed off, busy bump, pause. */
    public interface KitchenLive {
        LiveBoard board(String merchantId);

        /** "Accept · start cooking". Publishes {@code order.accepted}. */
        LiveBoard accept(String merchantId, String orderId, String actorId);

        /** "Mark ready". Publishes {@code order.ready}. */
        LiveBoard ready(String merchantId, String orderId, String actorId);

        /** "Handed to courier" / "Handed to customer". Publishes {@code order.handed_off} (food escrow releases). */
        LiveBoard handOff(String merchantId, String orderId, String actorId);

        /** "Busy · +5 min" (up to +30). */
        LiveBoard bumpPrep(String merchantId);

        /** "Reset". */
        LiveBoard resetPrep(String merchantId);

        /** "Pause new orders" — 30 minutes. Publishes {@code kitchen.paused}. */
        LiveBoard pause(String merchantId, String actorId);

        /** "Paused · resume". Publishes {@code kitchen.resumed}. */
        LiveBoard resume(String merchantId, String actorId);
    }

    /**
     * S-67: "Auto-pause if late orders ≥ N" enforced. {@link #check} compares a kitchen's late orders (accepted, past
     * their ready-by time) with its threshold and, on a change, marks it and publishes {@code kitchen.auto_paused} /
     * {@code kitchen.auto_resumed}; customers are refused at read time either way ({@code KitchenCalendar}).
     */
    public interface KitchenAutoPause {
        void check(String merchantId);

        /** Every kitchen with auto-pause on (the scheduler, once a minute); returns how many changed state. */
        int checkAll();
    }

    /** The board's auto-pause line: {@code active} while {@code lateOrders} ≥ {@code threshold} (null = off). */
    public record AutoPause(int lateOrders, @Nullable Integer threshold, boolean active) {}

    public record LiveLine(int qty, String title, List<String> modifiers) {}

    /**
     * Who collects the food and where they are. {@code party}: courier | customer. {@code state}: {@code finding}
     * (no courier yet) · {@code assigned} · {@code arriving} (with {@code eta}) · {@code waiting} (at the counter) ·
     * {@code none} (pickup, no ETA shared).
     */
    public record Handoff(
            String party,
            String state,
            @Nullable String name,
            @Nullable Instant eta) {}

    /**
     * One ticket. {@code customerName} "A. Osei", or the host's first name for a group order ({@code groupSize} people).
     * {@code readyBy} is set once accepted.
     */
    public record LiveTicket(
            String orderId,
            @Nullable String ref,
            @Nullable String customerName,
            int groupSize,
            Instant placedAt,
            @Nullable Instant scheduledFor,
            KitchenStage stage,
            List<LiveLine> lines,
            String fulfilmentMode,
            Handoff handoff,
            @Nullable Instant readyBy) {}

    /** {@code open} = every ticket on the board; {@code fresh} = New. */
    public record LiveCounts(int open, int fresh, int cooking, int ready) {}

    /** Prep time customers see: {@code defaultPrepMin} + {@code bumpMin} = {@code shownMin}. */
    public record PrepShown(int defaultPrepMin, int bumpMin, int shownMin) {}

    public record LiveBoard(
            List<LiveTicket> items,
            LiveCounts counts,
            PrepShown prep,
            @Nullable Instant pausedUntil,
            AutoPause autoPause) {}

    // ── Hours, prep & capacity ────────────────────────────────────────────────

    public interface ViewKitchenSetup {
        SetupView setup(String merchantId);
    }

    public interface EditKitchenSetup {
        SetupView prep(String merchantId, PrepCommand command);

        SetupView fulfilment(String merchantId, FulfilmentCommand command);

        /** Replaces the weekly hours (all seven days). */
        SetupView hours(String merchantId, List<DayCommand> days);

        SetupView addHoliday(String merchantId, HolidayCommand holiday, String actorId);

        SetupView removeHoliday(String merchantId, String holidayId);
    }

    /** {@code autoPauseLate} null = Never. */
    public record PrepCommand(
            int defaultPrepMin,
            int maxOrdersPer15,
            long largeOrderCents,
            @Nullable Integer autoPauseLate) {}

    public record FulfilmentCommand(
            boolean courier,
            boolean pickup,
            boolean mealKits,
            boolean scheduled,
            int scheduledDays,
            boolean groupOrders,
            int groupMax,
            BigDecimal radiusKm,
            List<String> areas) {}

    public record DayCommand(
            int weekday,
            List<List<String>> ranges,
            @Nullable String note) {}

    public record HolidayCommand(
            LocalDate day,
            List<List<String>> ranges,
            @Nullable String note) {}

    public record PrepView(
            int defaultPrepMin,
            int bumpMin,
            int shownMin,
            int maxOrdersPer15,
            long largeOrderCents,
            int largeOrderAddMin,
            @Nullable Integer autoPauseLate) {}

    /** {@code pickupFromMin}–{@code pickupToMin}: when pickup orders are usually ready ("On · 15–20 min"). */
    public record FulfilmentView(
            boolean courier,
            boolean pickup,
            int pickupFromMin,
            int pickupToMin,
            boolean mealKits,
            boolean scheduled,
            int scheduledDays,
            boolean groupOrders,
            int groupMax,
            @Nullable BigDecimal radiusKm,
            List<String> areas) {}

    public record DayView(
            int weekday,
            List<List<String>> ranges,
            @Nullable String note) {}

    public record HolidayView(
            String id,
            LocalDate day,
            List<List<String>> ranges,
            @Nullable String note) {}

    public record MenuScheduleView(String menuId, String name, MenuStatus status, MenuSchedule schedule) {}

    public record SetupView(
            PrepView prep,
            @Nullable Instant pausedUntil,
            FulfilmentView fulfilment,
            List<DayView> hours,
            List<HolidayView> holidays,
            List<MenuScheduleView> menus,
            FoodSafety foodSafety) {}
}
