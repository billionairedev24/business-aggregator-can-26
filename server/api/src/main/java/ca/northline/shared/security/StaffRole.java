package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Northline staff roles in the platform console (S-90, design 03 {@code ROLES}). Granted in
 * {@code identity.platform_roles} next to {@code staff} (the console's on/off switch: no {@code staff}, no console) and
 * carried in the access token's {@code roles} claim. Each role opens a set of screens and allows a set of actions; a
 * person may hold several and narrow the console to one ("Switch role view", {@code X-Console-Role}).
 */
public enum StaffRole implements CodedEnum {
    ADMIN,
    TRUST_SAFETY,
    DISPATCH,
    FINANCE,
    SUPPORT,
    /** S-83: a support agent who also keeps the desk's macros (design: "support leads"). */
    SUPPORT_LEAD,
    ANALYST,
    /** S-105: the privacy officer — people's access, correction and erasure requests. */
    PRIVACY,
    /** S-120: merchant success — recruits and onboards pilot businesses, schedules and records kitchen visits. */
    MERCHANT_SUCCESS;

    /** The platform role that opens the console at all ({@code /api/v1/console/**}). */
    public static final String STAFF = "staff";

    /** The screens this role opens (besides the ones every staff member has, {@link ConsoleScreen#openToAllStaff}). */
    public Set<ConsoleScreen> screens() {
        return switch (this) {
            case ADMIN -> Set.copyOf(EnumSet.allOf(ConsoleScreen.class));
            case TRUST_SAFETY ->
                Set.of(
                        ConsoleScreen.OVERVIEW,
                        ConsoleScreen.SELLERS,
                        ConsoleScreen.VERIFY,
                        ConsoleScreen.VETTING,
                        ConsoleScreen.TRUST,
                        ConsoleScreen.DISPUTES,
                        ConsoleScreen.SUPPORT,
                        ConsoleScreen.TEAM,
                        // S-120: trust & safety approve pilot businesses, so they see the pipeline (no onboarding
                        // action)
                        ConsoleScreen.PILOT);
            case DISPATCH ->
                Set.of(ConsoleScreen.OVERVIEW, ConsoleScreen.ORDERS, ConsoleScreen.DELIVERY, ConsoleScreen.SUPPORT);
            case FINANCE ->
                Set.of(
                        ConsoleScreen.OVERVIEW,
                        ConsoleScreen.FINANCE,
                        ConsoleScreen.REPORTS,
                        ConsoleScreen.DISPUTES,
                        ConsoleScreen.TEAM);
            case SUPPORT ->
                Set.of(
                        ConsoleScreen.OVERVIEW,
                        ConsoleScreen.SUPPORT,
                        ConsoleScreen.ORDERS,
                        ConsoleScreen.SELLERS,
                        ConsoleScreen.DISPUTES,
                        ConsoleScreen.UAT);
            // S-105: support leads take the privacy requests that arrive at the help desk
            case SUPPORT_LEAD ->
                Set.of(
                        ConsoleScreen.OVERVIEW,
                        ConsoleScreen.SUPPORT,
                        ConsoleScreen.ORDERS,
                        ConsoleScreen.SELLERS,
                        ConsoleScreen.DISPUTES,
                        ConsoleScreen.PRIVACY,
                        ConsoleScreen.UAT);
            case ANALYST -> Set.of(ConsoleScreen.OVERVIEW, ConsoleScreen.REPORTS);
            case PRIVACY -> Set.of(ConsoleScreen.OVERVIEW, ConsoleScreen.PRIVACY, ConsoleScreen.SUPPORT);
            case MERCHANT_SUCCESS ->
                Set.of(
                        ConsoleScreen.OVERVIEW,
                        ConsoleScreen.PILOT,
                        ConsoleScreen.SELLERS,
                        ConsoleScreen.SUPPORT,
                        ConsoleScreen.UAT);
        };
    }

    /** What this role may change on the screens it opens. */
    public Set<ConsoleAction> actions() {
        return switch (this) {
            case ADMIN -> Set.copyOf(EnumSet.allOf(ConsoleAction.class));
            case TRUST_SAFETY ->
                Set.of(
                        ConsoleAction.SUSPEND,
                        ConsoleAction.DECIDE,
                        ConsoleAction.VERIFY,
                        ConsoleAction.VET,
                        ConsoleAction.SUPPORT);
            case DISPATCH -> Set.of(ConsoleAction.DISPATCH);
            case FINANCE -> Set.of(ConsoleAction.REFUND, ConsoleAction.PAYOUTS);
            // S-121: support triages the pilot group's feedback, as it does their cases
            case SUPPORT -> Set.of(ConsoleAction.SUPPORT, ConsoleAction.UAT);
            case SUPPORT_LEAD ->
                Set.of(ConsoleAction.SUPPORT, ConsoleAction.MACROS, ConsoleAction.PRIVACY, ConsoleAction.UAT);
            case ANALYST -> Set.of();
            case PRIVACY -> Set.of(ConsoleAction.PRIVACY);
            // S-121: merchant success runs the pilot businesses' UAT with them (sign-offs, their feedback)
            case MERCHANT_SUCCESS -> Set.of(ConsoleAction.ONBOARD, ConsoleAction.UAT);
        };
    }

    public boolean opens(ConsoleScreen screen) {
        return screen.openToAllStaff() || screens().contains(screen);
    }

    public boolean allows(ConsoleAction action) {
        return actions().contains(action);
    }

    public static Optional<StaffRole> fromCode(@Nullable String code) {
        return Arrays.stream(values()).filter(r -> r.code().equals(code)).findFirst();
    }

    /** The console roles among a token's platform roles (case-insensitive; {@code staff} and others ignored). */
    public static Set<StaffRole> held(Collection<String> platformRoles) {
        var held = platformRoles.stream()
                .map(r -> r.toLowerCase(Locale.ROOT))
                .flatMap(r -> fromCode(r).stream())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StaffRole.class)));
        return Set.copyOf(held);
    }
}
