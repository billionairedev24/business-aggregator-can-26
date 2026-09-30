package ca.northline.payments.application;

/**
 * Internal (not in {@code api}): a new Stripe event was stored. Published in the receiving transaction, so the Modulith
 * registry (outbox) hands it to {@link StripeEventListener} after commit and again after a restart if it didn't finish.
 */
public record StripeEventReceived(String stripeEventId) {}
