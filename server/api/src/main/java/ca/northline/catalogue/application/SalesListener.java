package ca.northline.catalogue.application;

import ca.northline.booking.api.BookingProgressed;
import ca.northline.booking.api.QuoteAccepted;
import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.payments.api.RefundIssued;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code sales_30d} current (S-38). Every order, booking and refund event of a merchant triggers a recount of
 * that merchant's listings (async, after commit, retried from the event registry).
 */
@Component
@RequiredArgsConstructor
class SalesListener {

    private final RecountSales sales;

    /** S-51: a new order counts at once (before, it waited for packing or the nightly run). */
    @ApplicationModuleListener
    void on(OrderPlaced event) {
        sales.recount(event.merchantId());
    }

    @ApplicationModuleListener
    void on(OrderPacked event) {
        sales.recount(event.merchantId());
    }

    @ApplicationModuleListener
    void on(BookingProgressed event) {
        sales.recount(event.merchantId());
    }

    @ApplicationModuleListener
    void on(QuoteAccepted event) {
        sales.recount(event.merchantId());
    }

    @ApplicationModuleListener
    void on(RefundIssued event) {
        sales.recount(event.merchantId());
    }
}
