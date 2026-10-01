package ca.northline.hire.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/**
 * Where the accepted job happens, given only to the chosen provider when the customer accepts (the request itself
 * carries the area alone). The access instructions and the phone number are sealed with the booking (S-55's
 * {@code booking.access_notes}) and shown around the visit only.
 */
public record QuoteVisit(
        @Nullable String addressLine,
        @Nullable String unit,
        @Nullable String accessNote,
        @Nullable String contactPhone) {

    /** @param comesToCustomer the provider travels to the customer (visits, home services, events) */
    public QuoteVisit validate(boolean comesToCustomer) {
        var errors = new ArrayList<Violation>();
        if (comesToCustomer && blank(addressLine)) {
            errors.add(new Violation("addressLine", "required", BookingRequest.ADDRESS));
        } else if (nz(addressLine).length() > 200) {
            errors.add(new Violation("addressLine", "length", BookingRequest.TOO_LONG_200));
        }
        if (nz(unit).length() > 40) {
            errors.add(new Violation("unit", "length", "At most 40 characters."));
        }
        if (nz(accessNote).length() > 500) {
            errors.add(new Violation("accessNote", "length", BookingRequest.TOO_LONG_500));
        }
        if (!blank(contactPhone)
                && !BookingRequest.PHONE_FORMAT.matcher(nz(contactPhone)).matches()) {
            errors.add(new Violation("contactPhone", "format", BookingRequest.PHONE));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return this;
    }

    public @Nullable String address() {
        var line = trimmed(addressLine);
        var u = trimmed(unit);
        return line == null ? null : u == null ? line : line + ", " + u;
    }

    public @Nullable String note() {
        return trimmed(accessNote);
    }

    public @Nullable String phone() {
        return trimmed(contactPhone);
    }

    private static @Nullable String trimmed(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
