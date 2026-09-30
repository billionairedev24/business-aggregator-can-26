package ca.northline.catalogue.application;

import java.util.List;
import java.util.Map;

/** Outbound port: the {@code sales_30d} read model on {@code catalogue.offers} and {@code catalogue.services}. */
public interface SalesFigures {

    /** Sets {@code sales_30d} of every offer and service of the merchant: the given counts, 0 for the rest. */
    void replace(String merchantId, Map<String, Long> offers, Map<String, Long> services);

    /** Merchants with a listing whose {@code sales_30d} is above 0, so that old sales can age out. */
    List<String> merchantsWithSales();
}
