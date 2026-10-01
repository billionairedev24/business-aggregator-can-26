/**
 * Platform console composition (S-90, E-8): who the staff member is in the console (roles, screens, actions), the role
 * view switch, and screens that combine several modules (S-91 overview). It reads other modules only through their
 * public {@code api} packages. Screen-specific endpoints of one module (trust flags, registry reviews…) stay in that
 * module under {@code /api/v1/console/<module>/…}. docs/CONSOLE_PLAN.md.
 */
@ApplicationModule(displayName = "console")
@NullMarked
package ca.northline.console;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
