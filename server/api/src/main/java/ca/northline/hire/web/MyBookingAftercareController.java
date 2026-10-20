package ca.northline.hire.web;

import ca.northline.hire.application.BookingAftercare;
import ca.northline.hire.application.BookingAftercare.BookingRow;
import ca.northline.hire.application.BookingAftercare.CancelTerms;
import ca.northline.hire.application.BookingAftercare.RequestRow;
import ca.northline.hire.application.QuoteFlow;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-153: a customer's bookings and quote requests after the wizard — Account's lists, what cancelling costs and
 * cancelling (the late fee taken from the hold), paying a deposit quote's balance by hand, the "Accept" step's price
 * with a code and points. Cancelling and paying move money: {@code Idempotency-Key} (CLAUDE.md), the answer kept 24 h.
 */
@RestController
@RequiredArgsConstructor
class MyBookingAftercareController {

    private final BookingAftercare aftercare;
    private final QuoteFlow.PriceAcceptance prices;
    private final IdempotentRequests idempotent;

    /** @param paymentMethodId one of the customer's saved cards, or null for Stripe.js */
    record PayRequest(@Nullable String paymentMethodId) {}

    record QuotePriceRequest(
            @Nullable String promoCode, @Nullable Boolean usePoints) {}

    @GetMapping("/api/v1/me/bookings")
    ResponseEntity<ListResponse<BookingRow>> bookings(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(aftercare.bookings(user.userId())));
    }

    @GetMapping("/api/v1/me/quote-requests")
    ResponseEntity<ListResponse<RequestRow>> requests(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(aftercare.requests(user.userId())));
    }

    /** What cancelling now costs (free, or the late fee kept from the hold). */
    @GetMapping("/api/v1/me/bookings/{bookingId}/cancellation")
    CancelTerms cancellation(@PathVariable String bookingId, CurrentUser user) {
        return aftercare.cancellation(user.userId(), bookingId);
    }

    @PostMapping("/api/v1/me/bookings/{bookingId}/cancel")
    ResponseEntity<String> cancel(
            @PathVariable String bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        var answer = idempotent.run(
                "consumer:%s:booking-cancel:%s".formatted(user.userId(), bookingId),
                key,
                bookingId,
                HttpStatus.OK.value(),
                () -> aftercare.cancel(user.userId(), bookingId));
        return MyBookingsController.json(answer);
    }

    /** Pays the balance of a deposit quote from the booking page (when it couldn't be held automatically). */
    @PostMapping("/api/v1/me/bookings/{bookingId}/balance")
    ResponseEntity<String> payBalance(
            @PathVariable String bookingId,
            @RequestBody(required = false) @Nullable PayRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestHeader(value = "X-Step-Up", required = false) @Nullable String stepUp,
            CurrentUser user) {
        var card = body == null ? null : body.paymentMethodId();
        var answer = idempotent.run(
                "consumer:%s:booking-balance:%s".formatted(user.userId(), bookingId),
                key,
                card == null ? "" : card,
                HttpStatus.OK.value(),
                () -> aftercare.payBalance(user.userId(), bookingId, key, user.mfa(), stepUp, card));
        return MyBookingsController.json(answer);
    }

    @PostMapping("/api/v1/me/bookings/{bookingId}/balance/confirm")
    ResponseEntity<String> confirmBalance(
            @PathVariable String bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        var answer = idempotent.run(
                "consumer:%s:booking-balance-confirm:%s".formatted(user.userId(), bookingId),
                key,
                bookingId,
                HttpStatus.CREATED.value(),
                () -> aftercare.confirmBalance(user.userId(), bookingId));
        return MyBookingsController.json(answer);
    }

    /** The "Accept" step's price with a promo code and points (checks the code; holds nothing). */
    @PostMapping("/api/v1/me/quotes/{quoteId}/price")
    QuoteFlow.AcceptancePrice quotePrice(
            @PathVariable String quoteId,
            @RequestBody(required = false) @Nullable QuotePriceRequest body,
            CurrentUser user) {
        return prices.price(
                user.userId(),
                quoteId,
                body == null ? null : body.promoCode(),
                body != null && Boolean.TRUE.equals(body.usePoints()));
    }
}
