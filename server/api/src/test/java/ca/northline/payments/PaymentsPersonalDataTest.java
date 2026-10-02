package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-105: payments' part — saved cards go; payments, escrows, refunds and disputes stay as financial records without the
 * name; held money, an open refund or an open dispute holds the erasure.
 */
class PaymentsPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "payments";
    }

    private String escrow(Subject person, String state) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.escrows (id, ref_type, ref_id, merchant_id, amount_cents, state, kind, label,
                                                      customer_id, customer_name, occurred_at)
                        values (:id, 'booking', :ref, 'm1', 5000, :state, 'service', 'Brake pads', :u, 'D. Kowalski', now())
                        """)
                .param("id", id)
                .param("ref", Ids.next())
                .param("state", state)
                .param("u", person.userId())
                .update();
        return id;
    }

    private void card(Subject person) {
        jdbc.sql("""
                        insert into payments.customer_cards (customer_id, payment_method, brand, last4, exp_month,
                                                             exp_year, is_default, added_at)
                        values (:u, :pm, 'visa', '4242', 12, 2030, true, now())
                        """)
                .param("u", person.userId())
                .param("pm", "pm_" + Ids.next())
                .update();
    }

    @Test
    void settledMoneyStaysWithoutTheNameAndCardsGo() {
        var person = person();
        var released = escrow(person, "released");
        card(person);
        jdbc.sql("""
                        insert into payments.refunds (id, escrow_id, merchant_id, amount_cents, state, customer_name, what)
                        values (:id, :e, 'm1', 1000, 'paid', 'D. Kowalski', 'Wrong size')
                        """).param("id", Ids.next()).param("e", released).update();
        jdbc.sql("""
                        insert into payments.disputes (id, ref_type, ref_id, state, merchant_id, customer_name,
                                                       customer_statement, decision)
                        values (:id, 'escrow', :e, 'decided', 'm1', 'D. Kowalski', 'They never came', 'release')
                        """).param("id", Ids.next()).param("e", released).update();

        assertThat(records(person, "payments.escrows")).isEqualTo(1);
        assertThat(records(person, "payments.refunds")).isEqualTo(1);
        assertThat(records(person, "payments.disputes")).isEqualTo(1);
        assertThat(section(person, "payments.cards").orElseThrow().json()).contains("4242");

        var outcome = erase(person);

        var e = Map.of("e", released);
        assertThat(count(
                        "select count(*) from payments.customer_cards where customer_id = :u",
                        Map.of("u", person.userId())))
                .isZero();
        assertThat(text("select coalesce(customer_name, '-') || amount_cents from payments.escrows where id = :e", e))
                .isEqualTo("-5000");
        assertThat(text("select coalesce(customer_name, '-') from payments.refunds where escrow_id = :e", e))
                .isEqualTo("-");
        assertThat(text(
                        "select coalesce(customer_name, '-') || customer_statement from payments.disputes "
                                + "where ref_id = :e",
                        e))
                .isEqualTo("-They never came");
        assertThat(outcome.retained())
                .containsExactly(
                        new Kept<>("payments.payments", Retention.FINANCIAL_RECORDS),
                        new Kept<>("payments.disputes.statements", Retention.CHARGEBACK_EVIDENCE));
        assertThat(outcome.held()).isEmpty();
        assertIdempotent(person, outcome);
    }

    @Test
    void heldMoneyAndAnOpenDisputeHoldTheErasure() {
        var person = person();
        escrow(person, "held");
        var disputed = escrow(person, "disputed");
        jdbc.sql("""
                        insert into payments.disputes (id, ref_type, ref_id, state, merchant_id, customer_name)
                        values (:id, 'escrow', :e, 'open', 'm1', 'D. Kowalski')
                        """).param("id", Ids.next()).param("e", disputed).update();

        var outcome = erase(person);

        assertThat(outcome.held())
                .containsExactly(
                        new Kept<>("payments.escrows", Hold.ESCROW_HELD),
                        new Kept<>("payments.disputes", Hold.OPEN_DISPUTE));
        assertThat(text("select customer_name from payments.escrows where id = :e", Map.of("e", disputed)))
                .isEqualTo("D. Kowalski");
    }

    @Test
    void staffCorrectTheNameOnReceipts() {
        var person = person();
        var released = escrow(person, "released");

        inTransaction(() -> {
            contributor().correct(person, "receiptName", "Dana Kowalska");
            return true;
        });

        assertThat(text("select customer_name from payments.escrows where id = :e", Map.of("e", released)))
                .isEqualTo("Dana Kowalska");
        assertThatThrownBy(() -> contributor().correct(person, "receiptName", " "))
                .isInstanceOf(RuleViolation.class)
                .hasMessage("Enter the name as it should appear, up to 80 characters.");
    }
}
