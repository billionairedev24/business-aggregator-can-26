package ca.northline.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards a platform console handler ({@code /api/v1/console/**}, S-90): the caller is staff with {@code acr=mfa} and
 * holds a {@link StaffRole} that opens {@link #value()} and allows every one of {@link #actions()}. With the
 * {@value StaffAccess#ROLE_VIEW_HEADER} header only that role counts (the console's "Switch role view"). Otherwise
 * HTTP 403 ProblemDetail, {@code code} = {@code mfa_required} | {@code not_staff} | {@code role_not_held} |
 * {@code insufficient_role}.
 *
 * <p>Every handler under {@code /api/v1/console/} MUST carry it (on the method, or on the class as a default) —
 * handlers without it are denied at runtime and fail {@code ConsoleEndpointsTest}. The console only hides what a role
 * can't use; this is what refuses it.
 *
 * <pre>{@code
 * @PostMapping("/{id}/decision")
 * @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
 * FlagView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest body, CurrentStaff staff)
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresConsole {

    /** The screen the endpoint serves. */
    ConsoleScreen value();

    /** What the endpoint changes; none for a read. */
    ConsoleAction[] actions() default {};
}
