package ca.northline.payments;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL-level payments fixtures with fresh ULIDs (the test database is shared and never wiped). Postings mirror
 * {@code LedgerEntry}: released escrow credits {@code merchant:<id>} with the net.
 */
@TestComponent
@RequiredArgsConstructor
public class PaymentsFixture {

    private final JdbcClient jdbc;
    private final TestData data;

    /** A merchant (tier, type) with an owner, a Stripe connected account and an active TD ··3391 payout account. */
    public record Shop(String merchantId, String ownerId) {}

    public Shop shop(String type, String tier) {
        var merchantId = data.merchant(type, "Prairie Wrench");
        jdbc.sql("update merchants.merchants set tier = ? where id = ?")
                .params(tier, merchantId)
                .update();
        var owner = data.user("Owner " + merchantId);
        data.member(merchantId, owner, MerchantRole.OWNER);
        jdbc.sql(
                        "insert into payments.connected_accounts (merchant_id, stripe_account, instant_payouts) values (?, ?, true)")
                .params(merchantId, "acct_" + merchantId)
                .update();
        jdbc.sql("""
                        insert into payments.payout_accounts (id, merchant_id, method, institution_name, institution_number,
                               transit_number, last4, holder_name, external_ref, state, created_at, created_by, effective_at)
                        values (?, ?, 'manual', 'TD Canada Trust', '004', '12345', '3391', 'Prairie Wrench Automotive Ltd.',
                                'ba_test', 'active', now() - interval '400 days', ?, now() - interval '400 days')""").params(Ids.next(), merchantId, owner).update();
        return new Shop(merchantId, owner);
    }

    /** Adds a member with {@code role} to the shop; returns the user id. */
    public String member(Shop shop, MerchantRole role) {
        var user = data.user(role.code() + " " + shop.merchantId());
        data.member(shop.merchantId(), user, role);
        return user;
    }

    /** A job / order line held in escrow (fee at {@code bps}). Returns the escrow id. */
    public String escrow(
            String merchantId,
            String kind,
            String state,
            long amountCents,
            int bps,
            String label,
            String customer,
            Instant occurredAt,
            @Nullable Instant releaseAt) {
        var id = Ids.next();
        var pi = Ids.next();
        jdbc.sql("""
                        insert into payments.payment_intents (id, stripe_pi, customer_id, amount_cents, currency, capture_method, state)
                        values (?, ?, ?, ?, 'CAD', 'manual', 'authorized')""").params(pi, "pi_" + id, "cust-" + customer, amountCents).update();
        var fee = Math.round(amountCents * bps / 10_000.0);
        jdbc.sql("""
                        insert into payments.escrows (id, payment_intent_id, ref_type, ref_id, merchant_id, amount_cents,
                               release_at, released_at, state, kind, label, customer_id, customer_name, listing_name, source,
                               take_rate_bps, fee_cents, tax_cents, occurred_at, fulfilled_at)
                        values (?, ?, 'booking', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'search', ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        pi,
                        "bk-" + id,
                        merchantId,
                        amountCents,
                        ts(releaseAt),
                        state.equals("released") ? ts(releaseAt) : null,
                        state,
                        kind,
                        label,
                        "cust-" + customer,
                        customer,
                        label,
                        bps,
                        fee,
                        Math.round(amountCents * 0.05),
                        ts(occurredAt),
                        releaseAt == null ? null : ts(occurredAt))
                .update();
        if (state.equals("released")) {
            credit(merchantId, amountCents - fee, "escrow", id, releaseAt == null ? occurredAt : releaseAt);
        }
        return id;
    }

    /** Credits the merchant's balance (and balances it against the escrow account). */
    public void credit(String merchantId, long cents, String refType, String refId, Instant at) {
        jdbc.sql("""
                        insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
                        values (?, 'escrow', ?, 0, ?, ?, ?), (?, ?, 0, ?, ?, ?, ?)""")
                .params(
                        Ids.next(),
                        cents,
                        refType,
                        refId,
                        ts(at),
                        Ids.next(),
                        "merchant:" + merchantId,
                        cents,
                        refType,
                        refId,
                        ts(at))
                .update();
    }

    /** A dispute on an escrow (puts it on hold). Returns the dispute id. */
    public String dispute(String merchantId, String escrowId, long amountCents, String caseNumber) {
        var id = Ids.next();
        jdbc.sql("update payments.escrows set state = 'disputed' where id = ?")
                .params(escrowId)
                .update();
        jdbc.sql("""
                        insert into payments.disputes (id, ref_type, ref_id, merchant_id, case_number, subject, amount_cents,
                               customer_name, customer_statement, opened_by, evidence, state, respond_by, opened_at)
                        values (?, 'escrow', ?, ?, ?, 'Pre-purchase inspection', ?, 'A. Osei',
                                'Report missed a coolant leak the dealer found two days later.', 'cust-A. Osei',
                                '[]'::jsonb, 'open', now() + interval '3 days', now() - interval '1 day')""").params(id, escrowId, merchantId, caseNumber, amountCents).update();
        return id;
    }

    /** A refund case in seller review. Returns the refund id. */
    public String refundCase(String merchantId, String escrowId, long amountCents, String caseNumber) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.refunds (id, merchant_id, escrow_id, case_number, what, customer_name,
                               amount_cents, reason, charged_to, kind, auto, state, contest_by, created_at)
                        values (?, ?, ?, ?, 'Wiper blades wrong size', 'P. Nguyen', ?, 'customer_request', 'merchant',
                                'refund', ?, 'seller_review', now() + interval '1 day', now() - interval '2 hours')""")
                .params(id, merchantId, escrowId, caseNumber, amountCents, amountCents < 2500)
                .update();
        return id;
    }

    public long balance(String merchantId) {
        return jdbc.sql(
                        "select coalesce(sum(credit_cents - debit_cents), 0) from payments.ledger_entries where account = ?")
                .params("merchant:" + merchantId)
                .query(Long.class)
                .single();
    }

    public static String caseNumber(String prefix) {
        return prefix + "-T" + Ids.next().substring(16);
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    public static Instant hoursAgo(long h) {
        return Instant.now().minus(Duration.ofHours(h));
    }

    public static Instant inHours(long h) {
        return Instant.now().plus(Duration.ofHours(h));
    }
}
