package ca.northline.merchants.application;

import ca.northline.merchants.api.StorefrontVisits.DayVisits;
import java.time.LocalDate;
import java.util.List;

/** Outbound port (S-75): {@code merchants.storefront_visits}, one counter per business and day. */
public interface StorefrontVisitStore {

    void increment(String merchantId, LocalDate day);

    List<DayVisits> daily(String merchantId, LocalDate from, LocalDate to);

    long total(ca.northline.shared.MerchantScope scope, LocalDate from, LocalDate to);
}
