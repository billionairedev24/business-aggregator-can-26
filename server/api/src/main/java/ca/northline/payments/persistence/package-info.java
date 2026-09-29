/**
 * Spring Data JDBC rows + repositories for escrows, payouts, payout accounts and refunds; {@code JdbcClient} for the
 * rest (disputes carry {@code jsonb} evidence, the ledger is append-only, read models join and aggregate).
 */
@NullMarked
package ca.northline.payments.persistence;

import org.jspecify.annotations.NullMarked;
