/**
 * Standing (S-156): a business's and a customer's standing with Northline — the trust consequences in force, appeals,
 * coaching plans.
 *
 * <ul>
 *   <li><b>Consequences</b> ({@code standing.consequences}) are a registry over the switches their owners keep:
 *       suspension and search hiding (merchants, S-82: recorded from {@code MerchantSuspended} /
 *       {@code MerchantSearchVisibilityChanged}, staff's and the rules' alike), instant book off (catalogue's
 *       {@code InstantBook} pause), a job's escrow released later for a missing completion photo (payments'
 *       {@code EscrowReleaseDelayed}) and a customer's no-show warning (identity's {@code CustomerNoShows}). Each has
 *       the reason the business or customer sees, and staff can lift it.
 *   <li><b>The nightly job</b> applies S-93's rules that S-82 left as configuration: provider no-shows → instant book
 *       off (and on again once the window holds fewer), customer no-shows → the warning providers see and no instant
 *       booking. Trust decides who ({@code trust.api.TrustConsequences}); each owning module applies.
 *   <li><b>Appeals</b>: a business appeals a dispute decision an agent took against it once, and each suspension,
 *       search hiding or instant-book pause once, within 14 days, with a statement and files. Staff take it from the
 *       console's queue and decide with a reason: granted lifts the consequence or makes Northline pay the seller
 *       ({@code payments.api.DisputeAppeals}); denied leaves it. Never decided by whoever took the decision. Audited
 *       and emailed to the owners ({@code messaging.api.StandingNotice}).
 *   <li><b>Coaching plans</b> staff assign with tasks the business ticks off in the Studio.
 * </ul>
 *
 * Layout as in {@code merchants}.
 */
@ApplicationModule(displayName = "standing")
@NullMarked
package ca.northline.standing;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
