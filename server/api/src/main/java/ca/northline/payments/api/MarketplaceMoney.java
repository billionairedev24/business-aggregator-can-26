package ca.northline.payments.api;

import ca.northline.shared.Backlog;
import ca.northline.shared.MerchantScope;
import java.time.Instant;

/** Platform-wide money figures for the console overview (S-91), from the ledger, escrows and disputes. */
public interface MarketplaceMoney {

    /** Northline's net revenue in [from, to): the ledger's {@code revenue} account, credits minus debits. */
    long revenueCents(MerchantScope scope, Instant from, Instant to);

    /** Money held in escrow now. */
    long escrowHeldCents(MerchantScope scope);

    /** Disputes opened in [from, to). */
    long disputesOpened(MerchantScope scope, Instant from, Instant to);

    /** Disputes waiting for a Northline agent (state {@code agent} or {@code appealed}), oldest by opening. */
    Backlog disputesForAgents(MerchantScope scope);
}
