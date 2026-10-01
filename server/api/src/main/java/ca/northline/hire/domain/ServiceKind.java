package ca.northline.hire.domain;

import ca.northline.shared.CodedEnum;
import java.util.Map;
import java.util.Set;

/**
 * How a service is booked (design 06: "each category has a booking type"; {@code catalogue.categories.booking_type}):
 * a visit to the customer, hourly work at their home, a quoted event, an appointment at the provider's place, or a free
 * consultation. The seed taxonomy carries no booking type yet ({@code db/seed/categories.json}), so it comes from the
 * category's group with a few leaf exceptions (docs/DECISIONS.md, S-53).
 */
public enum ServiceKind implements CodedEnum {
    VISIT,
    HOME,
    EVENT,
    APPOINTMENT,
    CONSULT;

    private static final Map<String, ServiceKind> GROUPS = Map.ofEntries(
            Map.entry("automotive", VISIT),
            Map.entry("home-trades", VISIT),
            Map.entry("cleaning-and-property", HOME),
            Map.entry("events-and-hospitality", EVENT),
            Map.entry("personal-care-and-wellness", APPOINTMENT),
            Map.entry("pets", HOME),
            Map.entry("education-and-coaching", HOME),
            Map.entry("professional", CONSULT),
            Map.entry("tech-and-digital", VISIT),
            Map.entry("childcare-and-family", HOME));

    private static final Map<String, ServiceKind> LEAVES = Map.ofEntries(
            Map.entry("movers", EVENT),
            Map.entry("property-management", CONSULT),
            Map.entry("mobile-hair-and-makeup", HOME),
            Map.entry("home-care-aide", HOME),
            Map.entry("dog-grooming", APPOINTMENT),
            Map.entry("career-coach", CONSULT),
            Map.entry("life-coach", CONSULT),
            Map.entry("web-design", CONSULT),
            Map.entry("photography-editing", CONSULT));

    /** Groups whose jobs are about a vehicle (the design's "Vehicle" questions). */
    private static final Set<String> VEHICLE_GROUPS = Set.of("automotive");

    /**
     * @param categoryId {@code service.<group>.<leaf>}
     * @param stored {@code catalogue.categories.booking_type} when set (wins)
     */
    public static ServiceKind of(String categoryId, @org.jspecify.annotations.Nullable String stored) {
        if (stored != null && !stored.isBlank() && !"null".equals(stored)) {
            return CodedEnum.fromCode(ServiceKind.class, stored);
        }
        var parts = categoryId.split("\\.");
        if (parts.length >= 3 && LEAVES.containsKey(parts[2])) {
            return LEAVES.get(parts[2]);
        }
        return parts.length >= 2 ? GROUPS.getOrDefault(parts[1], VISIT) : VISIT;
    }

    public static boolean vehicle(String categoryId) {
        var parts = categoryId.split("\\.");
        return parts.length >= 2 && VEHICLE_GROUPS.contains(parts[1]);
    }

    /**
     * Customers can ask for quotes (design: {@code quoteable}): visits (diagnosis first) and events (always quoted).
     * Hourly home work is estimated, appointments are fixed, consultations are free.
     */
    public boolean quoteable() {
        return this == VISIT || this == EVENT;
    }

    /** Events are only ever quoted: a 25 % deposit on acceptance. */
    public boolean quoteOnly() {
        return this == EVENT;
    }

    /** The provider comes to the customer, so their service area must cover the customer's location. */
    public boolean comesToCustomer() {
        return this == VISIT || this == HOME || this == EVENT;
    }
}
