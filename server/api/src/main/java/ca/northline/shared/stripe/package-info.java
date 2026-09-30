/**
 * Stripe plumbing shared by the modules that talk to Stripe (payments: charges, transfers, payouts; merchants: Connect
 * accounts): one way to build a {@link com.stripe.StripeClient} with the pinned API version, and the derivation of the
 * {@code Idempotency-Key} sent on every mutating call. No business rules and no Spring types.
 */
@NamedInterface("stripe")
@NullMarked
package ca.northline.shared.stripe;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
