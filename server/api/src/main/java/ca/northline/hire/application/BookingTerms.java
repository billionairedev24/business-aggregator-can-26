package ca.northline.hire.application;

import ca.northline.availability.api.CancellationTerms;
import ca.northline.booking.api.BookingMoney;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-153: keeps the late-cancellation terms the customer was shown with the booking they paid for — a fixed fee capped at
 * the region's maximum share of this booking's price — so a later change of the provider's rules never changes it.
 */
@Component
@RequiredArgsConstructor
class BookingTerms {

    private final CancellationTerms terms;
    private final BookingMoney money;

    void keep(String merchantId, String bookingId, Instant startsAt, long priceCents) {
        var p = terms.of(merchantId);
        var fixed = Math.min(p.feeCents(), Math.max(0, priceCents) * p.maxFeeBps() / 10_000);
        money.terms(bookingId, p.windowHours(), p.feeBps(), fixed, p.freeUntil(startsAt));
    }
}
