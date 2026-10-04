package ca.northline.region.api;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Age-restricted purchases (owner decision 2026-10-04): the minimum age, whether delivery or pickup is allowed and the
 * local hours they may happen in, per province and {@link AgeClass} — rows of {@code region.age_rules} (V340). A province
 * without a row for a class doesn't receive that class. Code never names a province or an age; it asks here with the
 * delivery address's (or, for pickup, the business's) province.
 */
public interface AgeRules {

    Optional<AgeRule> rule(@Nullable String province, AgeClass ageClass);

    /** Every row, by province then class. */
    List<AgeRule> all();

    /** The highest minimum age any row asks: a verified age is kept capped at it ("verified over N"). */
    int highestMinimumAge();

    /**
     * What buying {@code classes} together needs in a province: the strictest class's age, and every class's rule.
     *
     * @return empty when a class has no rule there (it can't be sold there)
     */
    default Optional<Requirement> requirement(@Nullable String province, Collection<AgeClass> classes) {
        var rules = new java.util.ArrayList<AgeRule>();
        for (var c : classes.stream().distinct().sorted().toList()) {
            var rule = rule(province, c);
            if (rule.isEmpty()) {
                return Optional.empty();
            }
            rules.add(rule.get());
        }
        var age = rules.stream().mapToInt(AgeRule::minimumAge).max().orElse(0);
        return Optional.of(new Requirement(age, rules));
    }

    /**
     * @param deliveryFrom local start of the window deliveries may arrive in; null = no window (both null or both set)
     * @param source the law the row comes from (shown to staff, never to customers)
     */
    record AgeRule(
            String province,
            AgeClass ageClass,
            int minimumAge,
            boolean deliveryAllowed,
            boolean pickupAllowed,
            @Nullable LocalTime deliveryFrom,
            @Nullable LocalTime deliveryUntil,
            @Nullable LocalTime pickupFrom,
            @Nullable LocalTime pickupUntil,
            String source,
            boolean confirmed) {

        /** Is {@code at} (local time) inside the window? A window ending before it starts runs past midnight. */
        public boolean allows(boolean pickup, LocalTime at) {
            if (pickup ? !pickupAllowed : !deliveryAllowed) {
                return false;
            }
            var from = pickup ? pickupFrom : deliveryFrom;
            var until = pickup ? pickupUntil : deliveryUntil;
            if (from == null || until == null) {
                return true;
            }
            return from.isBefore(until)
                    ? !at.isBefore(from) && at.isBefore(until)
                    : !at.isBefore(from) || at.isBefore(until);
        }
    }

    /** @param minimumAge the strictest age among the classes */
    record Requirement(int minimumAge, List<AgeRule> rules) {
        public Requirement {
            rules = List.copyOf(rules);
        }

        public List<AgeClass> classes() {
            return rules.stream().map(AgeRule::ageClass).toList();
        }

        public boolean allows(boolean pickup, LocalTime at) {
            return rules.stream().allMatch(r -> r.allows(pickup, at));
        }
    }
}
