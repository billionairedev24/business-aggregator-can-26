package ca.northline.merchants.api;

import java.time.LocalDate;
import java.util.List;

/**
 * S-75: how many times the business's public page was opened, per day in the business's time zone. Counts only: no
 * visitor is identified, nothing about a visitor is stored.
 */
public interface StorefrontVisits {

    /** Each day in [from, to) with its visits; days without visits are absent. */
    List<DayVisits> daily(String merchantId, LocalDate from, LocalDate to);

    /** S-95: visits in [from, to) to every storefront in {@code scope} (the console's "browsed" funnel step). */
    long total(ca.northline.shared.MerchantScope scope, LocalDate from, LocalDate to);

    record DayVisits(LocalDate day, int visits) {}
}
