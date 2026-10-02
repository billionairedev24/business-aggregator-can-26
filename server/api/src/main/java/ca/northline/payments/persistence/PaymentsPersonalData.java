package ca.northline.payments.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.payments.application.SavedCardGateway;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, payments: the person's payments, held and released money, refunds and disputes, and saved cards (brand and
 * last four only — Northline never holds a card number).
 *
 * <p>Erasure: saved cards are detached from the person's Stripe customer and forgotten. Payments, escrows, refunds and
 * disputes are financial records and stay under the person's id with their name blanked; a dispute's statement stays
 * as chargeback evidence. Money still held, an open refund or an open dispute holds the erasure until it is settled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PaymentsPersonalData implements PersonalDataContributor {

    static final String RECEIPT_NAME = "receiptName";
    static final String NAME_LENGTH = "Enter the name as it should appear, up to 80 characters.";

    private final JdbcClient jdbc;
    private final SavedCardGateway cards;

    @Override
    public String module() {
        return "payments";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "payments.payments", "Payments", "Paiements", """
                        select id, amount_cents, currency, capture_method, state
                          from payments.payment_intents where customer_id = :u
                        """, p),
                section(jdbc, "payments.escrows", "Money held for your orders and jobs", "Sommes retenues", """
                        select id, kind, label, order_number, merchant_id, amount_cents, tax_cents, tip_cents, state,
                               occurred_at, release_at, released_at, customer_name
                          from payments.escrows where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "payments.refunds", "Refunds", "Remboursements", """
                        select r.id, r.case_number, r.what, r.kind, r.amount_cents, r.tax_cents, r.state, r.created_at,
                               r.decided_at, r.paid_at
                          from payments.refunds r join payments.escrows e on e.id = r.escrow_id
                         where e.customer_id = :u order by r.created_at
                        """, p),
                section(jdbc, "payments.disputes", "Disputes", "Litiges", """
                        select d.id, d.case_number, d.subject, d.amount_cents, d.state, d.decision, d.refund_cents,
                               d.customer_statement, d.opened_at, d.decided_at
                          from payments.disputes d join payments.escrows e on e.id = d.ref_id
                         where e.customer_id = :u order by d.opened_at
                        """, p),
                section(jdbc, "payments.cards", "Saved cards", "Cartes enregistrées", """
                        select brand, last4, exp_month, exp_year, is_default, added_at
                          from payments.customer_cards where customer_id = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        var saved = jdbc.sql("select payment_method from payments.customer_cards where customer_id = :u")
                .param("u", u)
                .query((rs, _) -> rs.getString(1))
                .list();
        for (var paymentMethod : saved) {
            try {
                cards.detach(paymentMethod, "erasure-" + paymentMethod);
            } catch (RuntimeException e) {
                // a retried step finds it already detached; the local row goes either way
                log.warn(
                        "Saved card {} not detached on erasure: {}",
                        paymentMethod,
                        e.getClass().getSimpleName());
            }
        }
        jdbc.sql("delete from payments.customer_cards where customer_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("""
                        update payments.escrows set customer_name = null, version = version + 1
                         where customer_id = :u and customer_name is not null and state not in ('held', 'disputed')
                        """).param("u", u).update();
        jdbc.sql("""
                        update payments.refunds r set customer_name = null, version = r.version + 1
                          from payments.escrows e
                         where e.id = r.escrow_id and e.customer_id = :u and r.customer_name is not null
                           and r.state in ('denied', 'paid')
                        """).param("u", u).update();
        jdbc.sql("""
                        update payments.disputes d set customer_name = null, version = d.version + 1
                          from payments.escrows e
                         where e.id = d.ref_id and e.customer_id = :u and d.customer_name is not null
                           and d.state = 'decided'
                        """).param("u", u).update();
        var outcome = Erasure.done()
                .retaining("payments.payments", Retention.FINANCIAL_RECORDS)
                .retaining("payments.disputes.statements", Retention.CHARGEBACK_EVIDENCE);
        if (count("select count(*) from payments.escrows where customer_id = :u and state = 'held'", u) > 0) {
            outcome = outcome.holding("payments.escrows", Hold.ESCROW_HELD);
        }
        if (count("""
                        select count(*) from payments.disputes d join payments.escrows e on e.id = d.ref_id
                         where e.customer_id = :u and d.state <> 'decided'
                        """, u) > 0) {
            outcome = outcome.holding("payments.disputes", Hold.OPEN_DISPUTE);
        }
        if (count("""
                        select count(*) from payments.refunds r join payments.escrows e on e.id = r.escrow_id
                         where e.customer_id = :u and r.state not in ('denied', 'paid')
                        """, u) > 0) {
            outcome = outcome.holding("payments.refunds", Hold.OPEN_REFUND);
        }
        return outcome;
    }

    @Override
    public Set<String> correctable() {
        return Set.of(RECEIPT_NAME);
    }

    @Override
    public void correct(Subject subject, String field, String value) {
        if (!RECEIPT_NAME.equals(field)) {
            throw new IllegalArgumentException("Not correctable here: " + field);
        }
        var name = value.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw RuleViolation.of(RECEIPT_NAME, "length", NAME_LENGTH);
        }
        var p = Map.of("u", subject.userId(), "name", name);
        jdbc.sql("update payments.escrows set customer_name = :name, version = version + 1 where customer_id = :u")
                .params(p)
                .update();
        jdbc.sql("""
                        update payments.refunds r set customer_name = :name, version = r.version + 1
                          from payments.escrows e where e.id = r.escrow_id and e.customer_id = :u
                        """).params(p).update();
        jdbc.sql("""
                        update payments.disputes d set customer_name = :name, version = d.version + 1
                          from payments.escrows e where e.id = d.ref_id and e.customer_id = :u
                        """).params(p).update();
    }

    private int count(String sql, String userId) {
        return jdbc.sql(sql).param("u", userId).query(Integer.class).single();
    }
}
