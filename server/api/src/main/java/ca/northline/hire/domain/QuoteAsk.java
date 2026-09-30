package ca.northline.hire.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A quote request as the customer writes it (design 06 {@code book} in quote mode: "Describe the job, get 3 quotes" —
 * the job, where, then "Who should quote?"). {@link #details()} is what every provider asked sees: never a street
 * address, an access code or a phone number (the area only; the address is given once a quote is accepted).
 */
public record QuoteAsk(
        String category,
        List<String> providers,
        @Nullable String description,
        @Nullable Vehicle vehicle,
        @Nullable LocalDate eventDate,
        @Nullable Integer guests,
        @Nullable String budget,
        @Nullable String note,
        @Nullable String area,
        @Nullable LocalDate preferredDate) {

    public static final int MAX_PROVIDERS = 3;
    public static final String PROVIDERS = "Choose 1 to 3 providers.";
    public static final String DESCRIBE = "Describe the job in at least 10 characters.";
    public static final String TOO_LONG_1000 = "At most 1,000 characters.";
    public static final String TOO_LONG_500 = "At most 500 characters.";
    public static final String TOO_LONG_80 = "At most 80 characters.";
    public static final String VEHICLE = BookingRequest.VEHICLE;
    public static final String EVENT_DATE = "Pick a date from tomorrow on.";
    public static final String GUESTS = BookingRequest.GUESTS;
    public static final String NOT_QUOTEABLE = "This service is booked directly — pick a time on a provider's page.";

    public record Vehicle(@Nullable String year, @Nullable String make, @Nullable String model) {}

    public QuoteAsk {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * Checks every rule and reports all broken ones at once.
     *
     * @param today in the market's time zone
     */
    public QuoteAsk validate(ServiceKind kind, boolean vehicleCategory, LocalDate today) {
        var errors = new ArrayList<Violation>();
        if (!kind.quoteable()) {
            errors.add(new Violation("category", "not_quoteable", NOT_QUOTEABLE));
        }
        if (providers.isEmpty()
                || providers.size() > MAX_PROVIDERS
                || providers.stream().distinct().count() != providers.size()) {
            errors.add(new Violation("providers", "range", PROVIDERS));
        }
        var text = nz(description);
        if (text.length() < 10) {
            errors.add(new Violation("description", "length", DESCRIBE));
        } else if (text.length() > 1000) {
            errors.add(new Violation("description", "length", TOO_LONG_1000));
        }
        if (vehicleCategory) {
            var v = vehicle;
            if (v == null || blank(v.year()) || blank(v.make()) || blank(v.model())) {
                errors.add(new Violation("vehicle", "required", VEHICLE));
            } else if (nz(v.year()).length() > 10 || nz(v.make()).length() > 40 || nz(v.model()).length() > 80) {
                errors.add(new Violation("vehicle", "length", TOO_LONG_80));
            }
        }
        if (kind == ServiceKind.EVENT) {
            var date = eventDate;
            if (date == null || !date.isAfter(today)) {
                errors.add(new Violation("eventDate", "range", EVENT_DATE));
            }
            if (guests == null || guests < 1 || guests > 2000) {
                errors.add(new Violation("guests", "range", GUESTS));
            }
        }
        if (preferredDate != null && preferredDate.isBefore(today)) {
            errors.add(new Violation("preferredDate", "range", EVENT_DATE));
        }
        if (nz(budget).length() > 80) {
            errors.add(new Violation("budget", "length", TOO_LONG_80));
        }
        if (nz(note).length() > 500) {
            errors.add(new Violation("note", "length", TOO_LONG_500));
        }
        if (nz(area).length() > 80) {
            errors.add(new Violation("area", "length", TOO_LONG_80));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return this;
    }

    /** The request's title: the category, plus the guests of an event or the vehicle. */
    public String title(String categoryName) {
        var v = vehicle;
        if (guests != null) {
            return categoryName + " · " + guests + " guests";
        }
        if (v != null && !blank(v.make())) {
            return categoryName + " · " + String.join(" ", nz(v.year()), nz(v.make()), nz(v.model())).strip();
        }
        return categoryName;
    }

    /** What the providers see besides the title, description and area. */
    public Map<String, Object> details() {
        var d = new LinkedHashMap<String, Object>();
        var v = vehicle;
        if (v != null && !blank(v.make())) {
            d.put("vehicle", String.join(" ", nz(v.year()), nz(v.make()), nz(v.model())).strip());
        }
        if (eventDate != null) {
            d.put("eventDate", eventDate.toString());
        }
        if (guests != null) {
            d.put("guests", guests);
        }
        if (!blank(budget)) {
            d.put("budget", nz(budget));
        }
        if (!blank(note)) {
            d.put("note", nz(note));
        }
        return d;
    }

    public String text() {
        return nz(description);
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
