package ca.northline.payments.persistence;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.SaleFacts;
import ca.northline.payments.application.PlusInvoiceStore;
import ca.northline.payments.application.RewardChargeStore;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * S-152: {@link SaleFacts} over {@code payments.escrows} and its refunds, {@link RewardChargeStore} over
 * {@code payments.reward_charges} and {@link PlusInvoiceStore} over {@code payments.plus_invoices} (V426).
 */
@Repository
@RequiredArgsConstructor
class LoyaltyMoneyJdbc implements SaleFacts, RewardChargeStore, PlusInvoiceStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Fact> of(String escrowId) {
        return jdbc.sql("""
                        select e.id, e.merchant_id, e.customer_id, coalesce(e.kind, 'goods') as kind, e.ref_type,
                               e.ref_id, coalesce(e.amount_cents, 0) as amount, e.listing_id, e.order_number,
                               coalesce(e.label, '') as label, coalesce(e.occurred_at, e.created_at) as held_at,
                               coalesce((select sum(r.amount_cents) from payments.refunds r
                                          where r.escrow_id = e.id and r.state in ('approved', 'paid')
                                            and r.charged_to = 'merchant'), 0) as refunded,
                               array(select r.id from payments.refunds r
                                      where r.escrow_id = e.id and r.state in ('approved', 'paid')
                                        and r.charged_to = 'merchant' order by r.id) as refund_ids
                          from payments.escrows e where e.id = :id
                        """)
                .param("id", escrowId)
                .query((rs, _) -> new Fact(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("customer_id"),
                        CodedEnum.fromCode(EscrowKind.class, rs.getString("kind")),
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        rs.getLong("amount"),
                        rs.getLong("refunded"),
                        java.util.Arrays.asList(
                                (String[]) rs.getArray("refund_ids").getArray()),
                        rs.getString("listing_id"),
                        rs.getString("order_number"),
                        rs.getString("label"),
                        JdbcTimes.requiredInstant(rs, "held_at")))
                .optional();
    }

    @Override
    public boolean insert(
            String id, String ref, String kind, String merchantId, String escrowId, long cents, Instant at) {
        return jdbc.sql("""
                        insert into payments.reward_charges (id, ref, kind, merchant_id, escrow_id, cents, created_at)
                        values (:id, :ref, :kind, :m, :e, :cents, :at)
                        on conflict (ref) do nothing
                        """)
                        .param("id", id)
                        .param("ref", ref)
                        .param("kind", kind)
                        .param("m", merchantId)
                        .param("e", escrowId)
                        .param("cents", cents)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public void stripeObject(String id, @Nullable String stripeId) {
        jdbc.sql("update payments.reward_charges set transfer_reversal = :s where id = :id")
                .param("id", id)
                .param("s", stripeId)
                .update();
    }

    @Override
    public boolean insert(
            String id,
            String provider,
            String invoiceId,
            String subscriptionId,
            String householdId,
            String plan,
            long amountCents,
            long taxCents,
            Instant paidAt) {
        return jdbc.sql("""
                        insert into payments.plus_invoices (id, provider, invoice_id, subscription_id, household_id, plan,
                                                            amount_cents, tax_cents, paid_at)
                        values (:id, :provider, :invoice, :sub, :household, :plan, :amount, :tax, :paid)
                        on conflict (invoice_id) do nothing
                        """)
                        .param("id", id)
                        .param("provider", provider)
                        .param("invoice", invoiceId)
                        .param("sub", subscriptionId)
                        .param("household", householdId)
                        .param("plan", plan)
                        .param("amount", amountCents)
                        .param("tax", taxCents)
                        .param("paid", JdbcTimes.ts(paidAt))
                        .update()
                == 1;
    }
}
