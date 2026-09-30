/**
 * Adapters of the {@link ca.northline.auth.application.RateLimiter} port (S-9): Valkey/Redis (one Lua script, shared
 * by every instance) and an in-memory fallback for local runs and tests.
 */
@NullMarked
package ca.northline.auth.ratelimit;

import org.jspecify.annotations.NullMarked;
