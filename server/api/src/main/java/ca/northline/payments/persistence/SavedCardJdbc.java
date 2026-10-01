package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.application.PaymentMethods.Payment;
import ca.northline.payments.application.PaymentMethods.SavedCard;
import ca.northline.payments.application.PaymentMethods.SavedCardStore;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link SavedCardStore}: {@code payments.customer_cards} (V162 — brand, last four, expiry and the default flag of each
 * saved card, refreshed from Stripe on every list; never a card number) and the customer's billing history from their
 * escrows and PaymentIntents.
 */
@Repository
@RequiredArgsConstructor
class SavedCardJdbc implements SavedCardStore {

    private final JdbcClient jdbc;

    @Override
    public void replace(String customerId, List<SavedCard> cards) {
        jdbc.sql("delete from payments.customer_cards where customer_id = :c")
                .param("c", customerId)
                .update();
        for (var card : cards) {
            jdbc.sql("""
                            insert into payments.customer_cards (customer_id, payment_method, brand, last4, exp_month,
                                   exp_year, is_default, added_at)
                            values (:c, :pm, :brand, :last4, :m, :y, :def, :at)
                            """)
                    .param("c", customerId)
                    .param("pm", card.id())
                    .param("brand", card.brand())
                    .param("last4", card.last4())
                    .param("m", card.expMonth())
                    .param("y", card.expYear())
                    .param("def", card.isDefault())
                    .param("at", ts(card.addedAt()))
                    .update();
        }
    }

    @Override
    public List<SavedCard> cards(String customerId) {
        return jdbc.sql("""
                        select payment_method, brand, last4, exp_month, exp_year, is_default, added_at
                          from payments.customer_cards where customer_id = :c
                         order by is_default desc, added_at desc
                        """)
                .param("c", customerId)
                .query((rs, _) -> new SavedCard(
                        rs.getString("payment_method"),
                        rs.getString("brand"),
                        rs.getString("last4"),
                        rs.getInt("exp_month"),
                        rs.getInt("exp_year"),
                        rs.getBoolean("is_default"),
                        requiredInstant(rs, "added_at")))
                .list();
    }

    @Override
    public List<Payment> payments(String customerId, int limit) {
        return jdbc.sql("""
                        select * from (
                          select e.id, coalesce(e.occurred_at, e.created_at) as at, coalesce(e.label, '') as what,
                                 e.order_number as ref,
                                 coalesce(e.amount_cents, 0) + e.tax_cents + e.platform_fee_cents + e.platform_tax_cents
                                   + e.tip_cents as amount,
                                 c.brand, c.last4, coalesce(e.state, 'held') as status
                            from payments.escrows e
                            left join payments.payment_intents p on p.id = e.payment_intent_id
                            left join payments.customer_cards c
                                   on c.customer_id = e.customer_id and c.payment_method = p.payment_method_ref
                           where e.customer_id = :c
                          union all
                          select p.id, coalesce(p.authorized_at, p.created_at), 'delivery', null, p.amount_cents,
                                 c.brand, c.last4, coalesce(p.state, 'authorized')
                            from payments.payment_intents p
                            left join payments.customer_cards c
                                   on c.customer_id = p.customer_id and c.payment_method = p.payment_method_ref
                           where p.customer_id = :c and p.ref_type = 'order_delivery'
                        ) payments
                        order by at desc, id desc
                        limit :limit
                        """)
                .param("c", customerId)
                .param("limit", limit)
                .query((rs, _) -> new Payment(
                        rs.getString("id"),
                        requiredInstant(rs, "at"),
                        rs.getString("what"),
                        rs.getString("ref"),
                        rs.getLong("amount"),
                        rs.getString("brand"),
                        rs.getString("last4"),
                        Objects.requireNonNullElse(rs.getString("status"), "held")))
                .list();
    }
}
