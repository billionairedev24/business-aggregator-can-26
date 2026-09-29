/**
 * Shared kernel: ids, money, coded enums, domain events and the domain error types ({@link ca.northline.shared.RuleViolation},
 * {@link ca.northline.shared.NotFound}, {@link ca.northline.shared.Conflict}). No business logic, no Spring types in
 * this package — domain code in every module may use it. Web/security infrastructure lives in the sub-packages:
 * {@code shared.security} (named interface "security") is public; {@code shared.web} is internal.
 */
@NullMarked
package ca.northline.shared;

import org.jspecify.annotations.NullMarked;
