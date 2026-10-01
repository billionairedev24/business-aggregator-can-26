package ca.northline.hire.web;

import ca.northline.hire.application.BookingCheckout.Calendar;
import ca.northline.hire.application.BookingCheckout.ConfirmBooking;
import ca.northline.hire.application.BookingCheckout.Confirmation;
import ca.northline.hire.application.BookingCheckout.HoldSlot;
import ca.northline.hire.application.BookingCheckout.HoldView;
import ca.northline.hire.application.BookingCheckout.ReleaseSlot;
import ca.northline.hire.application.BookingCheckout.StartCheckout;
import ca.northline.hire.application.BookingCheckout.ViewBooking;
import ca.northline.hire.application.BookingCheckout.ViewCalendar;
import ca.northline.hire.domain.BookingRequest;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The booking wizard (S-55). The provider's calendar is public; holding a slot, paying and the confirmation need a
 * signed-in customer (any consumer token; paying follows the S-51 step-up rule, see {@link StartCheckout}). Paying and
 * confirming are money-moving: {@code Idempotency-Key} required (CLAUDE.md), the answer kept 24 h.
 */
@RestController
@RequiredArgsConstructor
class MyBookingsController {

    static final String SLOT_REQUIRED = "Pick a time.";
    static final String SERVICE_REQUIRED = "Pick a service.";

    private final ViewCalendar calendars;
    private final HoldSlot holds;
    private final ReleaseSlot releases;
    private final StartCheckout checkouts;
    private final ConfirmBooking confirmations;
    private final ViewBooking bookings;
    private final IdempotentRequests idempotent;

    record HoldRequest(
            @NotBlank(message = SERVICE_REQUIRED) String slug,
            @NotBlank(message = SERVICE_REQUIRED) String serviceId,
            @NotNull(message = SLOT_REQUIRED) Instant startsAt,
            @Nullable BigDecimal hours) {}

    /** The provider's live calendar for a service (the signed-in customer's own hold counts as free). */
    @GetMapping("/api/v1/public/providers/{slug}/slots")
    Calendar slots(
            @PathVariable String slug,
            @RequestParam String serviceId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(defaultValue = "7") int days,
            @Nullable Principal principal) {
        return calendars.calendar(slug, serviceId, from, days, principal == null ? null : principal.getName());
    }

    @PostMapping("/api/v1/me/bookings/holds")
    @ResponseStatus(HttpStatus.CREATED)
    HoldView hold(@Valid @RequestBody HoldRequest body, CurrentUser user) {
        return holds.hold(user.userId(), body.slug(), body.serviceId(), body.startsAt(), body.hours());
    }

    @DeleteMapping("/api/v1/me/bookings/holds/{holdId}")
    ResponseEntity<Void> release(@PathVariable String holdId, CurrentUser user) {
        releases.release(user.userId(), holdId);
        return ResponseEntity.noContent().build();
    }

    /** Prices the booking and opens the escrow payment (or books a free consultation at once). */
    @PostMapping("/api/v1/me/bookings/checkout")
    ResponseEntity<String> checkout(
            @RequestBody BookingRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestHeader(value = "X-Step-Up", required = false) @Nullable String stepUp,
            CurrentUser user) {
        var answer = idempotent.run(
                "consumer:%s:booking-checkout".formatted(user.userId()),
                key,
                body,
                HttpStatus.OK.value(),
                () -> checkouts.start(user.userId(), body, key, user.mfa(), stepUp));
        return json(answer);
    }

    /** The card is authorized: record the escrow hold and write the booking. */
    @PostMapping("/api/v1/me/bookings/holds/{holdId}/confirm")
    ResponseEntity<String> confirm(
            @PathVariable String holdId,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        var answer = idempotent.run(
                "consumer:%s:booking-confirm".formatted(user.userId()),
                key,
                holdId,
                HttpStatus.CREATED.value(),
                () -> confirmations.confirm(user.userId(), holdId));
        return json(answer);
    }

    @GetMapping("/api/v1/me/bookings/{bookingId}")
    Confirmation booking(@PathVariable String bookingId, CurrentUser user) {
        return bookings.booking(user.userId(), bookingId);
    }

    static ResponseEntity<String> json(IdempotentRequests.Outcome answer) {
        var response = ResponseEntity.status(answer.status()).contentType(MediaType.APPLICATION_JSON);
        if (answer.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(answer.body());
    }
}
