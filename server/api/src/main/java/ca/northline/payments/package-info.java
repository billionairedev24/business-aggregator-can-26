/**
 * Payments: escrow (Stripe Connect Express, manual capture), the double-entry ledger, payouts (scheduled, instant,
 * bank account changes), refunds and disputes, and the Studio's Finance screens (Earnings, Reports, Payouts, Refunds
 * &amp; disputes).
 *
 * <pre>
 *   payments/api          PUBLIC: events (escrow.released, refund.issued, dispute.decided, payout.sent,
 *                         payout_account.changed) + ports other modules call (EscrowLifecycle, CustomerCases,
 *                         DisputeDecisions, EarningsQuery)
 *   payments/web          REST adapters under /api/v1/merchants/{merchantId}/…, idempotency + step-up at the edge
 *   payments/application  use cases, services, outbound ports (gateways, stores, read models)
 *   payments/domain       Escrow, Payout, PayoutAccount, PayoutSchedule, Refund, Dispute, ledger postings, fees
 *   payments/persistence  Spring Data JDBC rows + JdbcClient read models, idempotency store
 *   payments/infra        Stripe + fake gateways, step-up proof verification, evidence storage, scheduled jobs
 * </pre>
 */
@ApplicationModule(displayName = "payments")
@NullMarked
package ca.northline.payments;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
