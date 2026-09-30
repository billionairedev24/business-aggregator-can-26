package ca.northline.hire.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the customer entered in the booking wizard (design 06 {@code book}: job details, location &amp; access, schedule
 * choices, policies). The rules follow the design's {@code detailsIncomplete} / {@code locIncomplete} /
 * {@code cannotPay}; the messages are ours (validation-rules.md has no booking section; docs/DECISIONS.md, S-55).
 * {@link #details()} is what the provider sees before the visit; access instructions and the phone number stay out of
 * it (sealed apart, shown only around the visit).
 */
public record BookingRequest(
        String holdId,
        String serviceId,
        @Nullable String description,
        @Nullable String urgency,
        @Nullable Vehicle vehicle,
        @Nullable Home home,
        @Nullable Event event,
        @Nullable String staff,
        @Nullable Consult consult,
        @Nullable BigDecimal hours,
        @Nullable String addressLine,
        @Nullable String unit,
        @Nullable String area,
        @Nullable String spot,
        @Nullable String accessNote,
        @Nullable String present,
        @Nullable String contactPhone,
        @Nullable String contactPreference,
        @Nullable String flexibility,
        boolean agreePolicies,
        boolean agreeTerms) {

    public static final String DESCRIBE = "Describe the problem in at least 10 characters.";
    public static final String TOO_LONG_500 = "At most 500 characters.";
    public static final String TOO_LONG_200 = "At most 200 characters.";
    public static final String TOO_LONG_80 = "At most 80 characters.";
    public static final String VEHICLE = "Tell us the vehicle year, make and model.";
    public static final String HOME_TYPE = "Choose the type of home.";
    public static final String HOME_BEDS = "Choose the number of bedrooms.";
    public static final String HOURS = "Choose between 1 and 12 hours, in half hours.";
    public static final String EVENT_DATE = "Pick the date of the event.";
    public static final String GUESTS = "Enter the number of guests (1 to 2,000).";
    public static final String GOAL = "Pick what you're looking to do.";
    public static final String ADDRESS = "Pick or enter the address.";
    public static final String OPTION = "Choose one of the options.";
    public static final String ACCESS = "Add access instructions (3+ characters).";
    public static final String PHONE = "Enter a phone number like +1 403 555 0123.";
    public static final String POLICIES = "Accept the cancellation policy to continue.";
    public static final String TERMS = "Agree to the Northline terms to continue.";

    static final Pattern PHONE_FORMAT = Pattern.compile("^\\+?[0-9][0-9 ().-]{6,19}$");

    public record Vehicle(
            @Nullable String year,
            @Nullable String make,
            @Nullable String model,
            @Nullable String plate,
            @Nullable String fuel) {}

    public record Home(
            @Nullable String type,
            @Nullable String beds,
            @Nullable String baths,
            @Nullable String size,
            @Nullable List<String> addons,
            @Nullable String pets) {}

    public record Event(
            @Nullable String date,
            @Nullable String time,
            @Nullable String duration,
            @Nullable Integer guests,
            @Nullable String occasion,
            @Nullable String venue,
            @Nullable List<String> style,
            @Nullable String budget,
            @Nullable String supplies) {}

    public record Consult(
            @Nullable String goal,
            @Nullable String timeline,
            @Nullable String priceRange,
            @Nullable String areas,
            @Nullable String preapproved,
            @Nullable String meeting) {}

    /**
     * Every broken rule at once (422, one error per field).
     *
     * @param vehicleCategory the category's jobs are about a vehicle (automotive)
     * @param free nothing is paid (a free consultation): no policies to accept
     */
    public BookingRequest validate(ServiceKind kind, boolean vehicleCategory, boolean free) {
        var errors = new ArrayList<Violation>();
        var text = nz(description);
        if ((kind == ServiceKind.VISIT || kind == ServiceKind.EVENT) && text.length() < 10) {
            errors.add(new Violation("description", "length", DESCRIBE));
        } else if (text.length() > 500) {
            errors.add(new Violation("description", "length", TOO_LONG_500));
        }
        if (kind == ServiceKind.VISIT && vehicleCategory) {
            var v = vehicle;
            if (v == null || blank(v.year())) {
                errors.add(new Violation("vehicle.year", "required", VEHICLE));
            }
            if (v == null || blank(v.make())) {
                errors.add(new Violation("vehicle.make", "required", VEHICLE));
            }
            if (v == null || blank(v.model())) {
                errors.add(new Violation("vehicle.model", "required", VEHICLE));
            } else if (nz(v.model()).length() > 80) {
                errors.add(new Violation("vehicle.model", "length", TOO_LONG_80));
            }
        }
        if (kind == ServiceKind.HOME) {
            var h = hours;
            if (h == null
                    || h.compareTo(BigDecimal.ONE) < 0
                    || h.compareTo(BigDecimal.valueOf(12)) > 0
                    || h.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
                errors.add(new Violation("hours", "range", HOURS));
            }
        }
        if (kind == ServiceKind.EVENT) {
            var e = event;
            if (e == null || blank(e.date())) {
                errors.add(new Violation("event.date", "required", EVENT_DATE));
            }
            var guests = e == null ? null : e.guests();
            if (guests == null || guests < 1 || guests > 2000) {
                errors.add(new Violation("event.guests", "range", GUESTS));
            }
        }
        if (kind == ServiceKind.CONSULT && (consult == null || blank(consult.goal()))) {
            errors.add(new Violation("consult.goal", "required", GOAL));
        }
        if (kind != ServiceKind.APPOINTMENT) {
            if (blank(addressLine)) {
                errors.add(new Violation("addressLine", "required", ADDRESS));
            } else if (nz(addressLine).length() > 200) {
                errors.add(new Violation("addressLine", "length", TOO_LONG_200));
            }
            if (kind == ServiceKind.VISIT || kind == ServiceKind.HOME) {
                if (blank(spot)) {
                    errors.add(new Violation("spot", "required", OPTION));
                }
                if (nz(accessNote).length() < 3) {
                    errors.add(new Violation("accessNote", "length", ACCESS));
                }
            }
        }
        if (nz(accessNote).length() > 500) {
            errors.add(new Violation("accessNote", "length", TOO_LONG_500));
        }
        if (!blank(contactPhone) && !PHONE_FORMAT.matcher(nz(contactPhone)).matches()) {
            errors.add(new Violation("contactPhone", "format", PHONE));
        }
        if (!free && !agreePolicies) {
            errors.add(new Violation("agreePolicies", "required", POLICIES));
        }
        if (!free && !agreeTerms) {
            errors.add(new Violation("agreeTerms", "required", TERMS));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return this;
    }

    /** What the provider reads before the visit: no access instructions, no phone number. */
    public Map<String, Object> details() {
        var d = new LinkedHashMap<String, Object>();
        put(d, "note", description);
        put(d, "urgency", urgency);
        var v = vehicle;
        if (v != null && !blank(v.make())) {
            var line = String.join(
                    " ",
                    List.of(nz(v.year()), nz(v.make()), nz(v.model())).stream()
                            .filter(s -> !s.isBlank())
                            .toList());
            d.put("vehicle", blank(v.plate()) ? line : line + " · " + nz(v.plate()));
            put(d, "fuel", v.fuel());
        }
        if (home != null) {
            d.put("home", home);
        }
        if (event != null) {
            d.put("event", event);
        }
        if (consult != null) {
            d.put("consult", consult);
        }
        put(d, "staff", staff);
        if (hours != null) {
            d.put("hours", hours);
        }
        put(d, "unit", unit);
        put(d, "spot", spot);
        put(d, "present", present);
        put(d, "contactPreference", contactPreference);
        put(d, "flexibility", flexibility);
        return d;
    }

    private static void put(Map<String, Object> d, String key, @Nullable String value) {
        if (!blank(value)) {
            d.put(key, nz(value));
        }
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
