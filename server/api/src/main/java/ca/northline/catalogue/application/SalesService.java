package ca.northline.catalogue.application;

import ca.northline.booking.api.ServiceSales;
import ca.northline.orders.api.OfferSales;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recounts a merchant's 30-day sales from the orders and booking modules' public queries. A recount is derived, so
 * repeating it (a retried event, the daily run) is harmless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class SalesService implements RecountSales {

    private final OfferSales offerSales;
    private final ServiceSales serviceSales;
    private final SalesFigures figures;
    private final Clock clock;

    @Override
    @Transactional
    public void recount(String merchantId) {
        var to = clock.instant();
        var from = to.minus(WINDOW);
        figures.replace(
                merchantId,
                offerSales.unitsByOffer(merchantId, from, to),
                serviceSales.bookingsByService(merchantId, from, to));
    }

    @Override
    public int recountAll() {
        var merchants = figures.merchantsWithSales();
        for (var merchantId : merchants) {
            try {
                recount(merchantId);
            } catch (RuntimeException ex) {
                log.warn("30-day sales recount failed for merchant {}; next run retries", merchantId, ex);
            }
        }
        return merchants.size();
    }
}
