/**
 * Merchants: businesses (provider · seller · kitchen · both), team members, verifications, storefronts.
 *
 * <p>Reference layout for every module (see docs/BACKEND_CONVENTIONS.md):
 * <pre>
 *   merchants/api          PUBLIC (named interface "api"): events + types other modules may use
 *   merchants/web          REST adapters, request/response records, MapStruct web mappers
 *   merchants/application  use-case interfaces (inbound ports), services, outbound ports
 *   merchants/domain       aggregates, value objects, rules — plain Java, no Spring
 *   merchants/persistence  Spring Data JDBC rows/repositories + adapters implementing the outbound ports
 * </pre>
 */
@ApplicationModule(displayName = "merchants")
@NullMarked
package ca.northline.merchants;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
