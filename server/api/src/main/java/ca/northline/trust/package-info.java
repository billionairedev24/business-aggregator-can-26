/**
 * Trust: verified two-way reviews (generated from completed bookings and delivered orders — the business may reply
 * once and report, never edit), the nightly quality score, and trust &amp; safety flags (review reports, off-platform
 * payment attempts detected in messages). Layout as in {@code merchants} (docs/BACKEND_CONVENTIONS.md).
 */
@ApplicationModule(displayName = "trust")
@NullMarked
package ca.northline.trust;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
