package ca.northline.payments.application;

/**
 * Internal (not in {@code api}): a tax transaction was stored {@code pending}. Published in the same transaction, so
 * the Modulith registry (outbox) hands it to {@link TaxSyncListener} after commit, and again after a restart.
 */
public record TaxSyncRequested(String reference) {}
