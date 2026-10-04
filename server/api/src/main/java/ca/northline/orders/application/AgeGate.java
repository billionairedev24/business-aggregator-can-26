package ca.northline.orders.application;

import ca.northline.orders.application.CheckoutUseCases.CheckoutAge;
import ca.northline.orders.domain.CheckoutMessages;
import ca.northline.region.api.AgeClass;
import ca.northline.region.api.AgeRules;
import ca.northline.region.api.AgeRules.Requirement;
import ca.northline.region.api.Regions;
import ca.northline.restricted.api.AgeVerifications;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Age-restricted purchases (owner decision 2026-10-04: "age should be based on items customer is purchasing"): a cart
 * with restricted items needs a customer verified for the strictest class in it, under the rules of the delivery
 * address's province (the kitchen's for a pickup); the class must be sold there, delivered or picked up there, and
 * inside its hours. A cart without restricted items never reaches any of this.
 */
@Component
@RequiredArgsConstructor
class AgeGate {

    private final AgeRules rules;
    private final AgeVerifications verifications;
    private final Regions regions;

    /** The classes among line class codes (unknown codes count as nothing). */
    static List<AgeClass> classes(Collection<? extends @Nullable String> codes) {
        return codes.stream()
                .filter(Objects::nonNull)
                .flatMap(c -> AgeClass.of(c).stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * What the classes need in a province; empty when there are none.
     *
     * @param field the request field a province refusal is reported on
     */
    Optional<Requirement> requirement(List<AgeClass> classes, String province, boolean pickup, String field) {
        if (classes.isEmpty()) {
            return Optional.empty();
        }
        var requirement = rules.requirement(province, classes).orElseThrow(() -> refused(province, pickup, field));
        if (requirement.rules().stream().anyMatch(r -> pickup ? !r.pickupAllowed() : !r.deliveryAllowed())) {
            throw refused(province, pickup, field);
        }
        return Optional.of(requirement);
    }

    /**
     * The age the recipient proves at handoff, recorded on the order when it is placed: the strictest class's under the
     * province's rules (checked at checkout), or — should a rule have gone meanwhile — the strictest age any province
     * asks. Null without restricted lines. Never refuses: the money is already authorized.
     */
    @Nullable
    Integer idCheckAge(List<AgeClass> classes, String province) {
        if (classes.isEmpty()) {
            return null;
        }
        return rules.requirement(province, classes).map(Requirement::minimumAge).orElseGet(rules::highestMinimumAge);
    }

    /** Every handover time must fall inside the classes' windows, in the place's zone. */
    void checkTimes(Requirement requirement, boolean pickup, Collection<Instant> times, ZoneId zone) {
        for (var at : times) {
            if (!requirement.allows(pickup, at.atZone(zone).toLocalTime())) {
                throw new Conflict("restricted_hours", CheckoutMessages.AGE_HOURS);
            }
        }
    }

    /** 409 {@code age_verification_required} or {@code age_under_minimum} unless the customer is verified old enough. */
    void requireVerified(String userId, Requirement requirement) {
        var status = verifications.status(userId);
        if (status.ageFloor() >= requirement.minimumAge()) {
            return;
        }
        if (AgeVerifications.Status.VERIFIED.equals(status.state())
                && status.ageFloor() < requirement.minimumAge()
                && status.overAge() != null
                && status.overAge() < rules.highestMinimumAge()) {
            throw new Conflict("age_under_minimum", CheckoutMessages.AGE_UNDER.formatted(requirement.minimumAge()));
        }
        throw new Conflict(
                "age_verification_required", CheckoutMessages.AGE_VERIFY.formatted(requirement.minimumAge()));
    }

    /**
     * Checkout set-up, before an address is chosen: the classes in the cart and, when a saved address's province is
     * known, the age it asks. Never refuses (the quote does).
     */
    CheckoutAge preview(String userId, List<AgeClass> classes, @Nullable String province) {
        if (classes.isEmpty()) {
            return CheckoutAge.NONE;
        }
        var requirement =
                province == null ? null : rules.requirement(province, classes).orElse(null);
        if (requirement != null) {
            return view(userId, requirement);
        }
        var status = verifications.status(userId);
        return new CheckoutAge(
                true,
                0,
                classes.stream().map(AgeClass::code).toList(),
                status.ageFloor() > 0 ? "verified" : status.state());
    }

    /** What checkout shows: the age asked and where the customer stands. */
    CheckoutAge view(String userId, @Nullable Requirement requirement) {
        if (requirement == null) {
            return CheckoutAge.NONE;
        }
        var status = verifications.status(userId);
        var state = status.ageFloor() >= requirement.minimumAge()
                ? "verified"
                : AgeVerifications.Status.VERIFIED.equals(status.state()) ? "under_age" : status.state();
        return new CheckoutAge(
                true,
                requirement.minimumAge(),
                requirement.classes().stream().map(AgeClass::code).toList(),
                state);
    }

    private RuleViolation refused(String province, boolean pickup, String field) {
        var name = regions.provinceName(province, Locale.ENGLISH);
        return RuleViolation.of(
                field,
                "age_rules",
                (pickup ? CheckoutMessages.AGE_NO_PICKUP : CheckoutMessages.AGE_NOT_HERE).formatted(name));
    }
}
