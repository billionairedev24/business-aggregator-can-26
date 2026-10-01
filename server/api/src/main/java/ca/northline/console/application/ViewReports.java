package ca.northline.console.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — reports &amp; analytics (S-95, design 03 {@code reports}; admin, finance, analyst). Marketplace
 * health over 90 days from aggregates only: weekly active customers (this period and the one before), the shop funnel,
 * signup-month cohorts' repeat rate, top categories by sales and waitlist demand. No person is identifiable in the
 * answer: only counts leave the server, and any count from 1 to {@link #MIN_CELL} − 1 is withheld (null).
 */
public interface ViewReports {

    /** Smallest count the screen shows; smaller non-zero counts come back null (small-cell suppression). */
    int MIN_CELL = 5;

    /** {@code province} null = every province; else the region model's code (the businesses there). */
    Report report(@Nullable String province);

    /**
     * @param from the first day of the 90 days, in the place's zone
     * @param weeks 13 weeks, Monday first, the current one last
     */
    record Report(
            Instant asOf,
            @Nullable String province,
            LocalDate from,
            List<Week> weeks,
            List<Step> funnel,
            List<Cohort> cohorts,
            List<CategorySales> topCategories,
            List<Waitlist> waitlist) {

        public Report {
            weeks = List.copyOf(weeks);
            funnel = List.copyOf(funnel);
            cohorts = List.copyOf(cohorts);
            topCategories = List.copyOf(topCategories);
            waitlist = List.copyOf(waitlist);
        }
    }

    /** @param customers distinct customers who bought that week; {@code previous}: the same week 13 weeks earlier */
    record Week(
            LocalDate week,
            @Nullable Long customers,
            @Nullable Long previous) {}

    /**
     * @param step {@code app_opens} | {@code browsed} | {@code cart} | {@code checkout} | {@code paid}
     * @param count null when not recorded (app opens; carts when scoped) or withheld
     * @param recorded false when nothing records this step yet
     */
    record Step(String step, @Nullable Long count, boolean recorded) {}

    /**
     * Customers (people who bought, orders or bookings) by the month they signed up, and the share of them who bought
     * in each of the next three months (percent; null for a month still to come, or when withheld).
     */
    record Cohort(
            String month,
            @Nullable Long customers,
            @Nullable Double m1,
            @Nullable Double m2,
            @Nullable Double m3) {}

    record CategorySales(String categoryId, Map<String, String> names, long salesCents) {

        public CategorySales {
            names = Map.copyOf(names);
        }
    }

    record Waitlist(String province, @Nullable Long people) {}
}
