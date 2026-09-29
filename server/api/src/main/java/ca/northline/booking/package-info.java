/**
 * Booking: jobs and the job flow (confirmed → en route → on site → completed → signed off), itemized versioned quotes
 * in reply to quote requests, and mid-job approvals. Layout as in {@code merchants} (api / web / application / domain
 * / persistence).
 */
@ApplicationModule(displayName = "booking")
@NullMarked
package ca.northline.booking;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
