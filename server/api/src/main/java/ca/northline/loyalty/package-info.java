/**
 * Loyalty (S-152): points that completed sales earn, provider-funded rewards paying out, Invite a neighbour and the
 * Northline Plus membership.
 *
 * <ul>
 *   <li>Earning listens for {@code payments.api.EscrowReleased} (money released after the escrow window: a completed
 *       sale) and credits points by the region's rule ({@code region.api.LoyaltyRules}); Plus members earn more; a
 *       business's running reward adds its extra points ({@code trust.api.RewardCredits}) and payments charges it
 *       ({@code payments.api.RewardCharges}). {@code payments.api.RefundIssued} takes a refund's share back. The ledger
 *       is trust's ({@code trust.api.PointsLedger}).
 *   <li>Referrals: a code per person; a new neighbour claims one; both get points after the invitee's first completed
 *       sale, within abuse limits.
 *   <li>Plus: one membership per household, billed by payments ({@code payments.api.PlusBilling}: Stripe Billing or
 *       the local fake), changed by verified webhooks ({@code PlusSubscriptionUpdated}); benefits are configuration
 *       ({@code northline.plus.*}) and read by checkout through {@link ca.northline.loyalty.api.PlusBenefits}.
 * </ul>
 *
 * Layout as in {@code merchants}.
 */
@ApplicationModule(displayName = "loyalty")
@NullMarked
package ca.northline.loyalty;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
