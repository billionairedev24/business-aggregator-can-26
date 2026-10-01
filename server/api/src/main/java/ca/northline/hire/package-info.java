/**
 * Hiring a service business, the consumer side of design 06's Services journey (S-53 … S-56): the services landing,
 * categories and provider lists, the public provider page, the booking wizard and quote requests. A composition module
 * like {@code studio}: it owns no tables and talks only to other modules' public {@code api} packages (booking,
 * availability, catalogue, merchants, payments, trust). Nothing depends on it.
 */
@ApplicationModule(displayName = "hire")
@NullMarked
package ca.northline.hire;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
