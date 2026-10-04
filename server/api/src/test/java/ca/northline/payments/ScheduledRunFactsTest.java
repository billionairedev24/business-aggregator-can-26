package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.application.PayoutRepository;
import ca.northline.payments.application.PayoutRepository.ScheduledRunFacts;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Engineering follow-ups (S-119 F6): the one read behind the minutely payout run — schedule, last scheduled payout and
 * bank-change hold of every business with automatic payouts.
 */
@Import(PaymentsFixture.class)
class ScheduledRunFactsTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    PayoutRepository payouts;

    @Autowired
    JdbcClient jdbc;

    @Test
    void oneReadHasEveryBusinessesScheduleLastPayoutAndBankHold_manualOnesLeftOut() {
        var plain = fx.shop("provider", "master").merchantId();
        var paid = fx.shop("seller", "trusted").merchantId();
        var held = fx.shop("provider", "registered").merchantId();
        var manual = fx.shop("kitchen", "trusted").merchantId();
        var paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        jdbc.sql("""
                        insert into payments.payouts (id, merchant_id, amount_cents, kind, fee_cents, state, arrives_at, created_at)
                        values (?, ?, 1000, 'scheduled', 0, 'in_transit', now() + interval '2 days', ?)""").params(Ids.next(), paid, java.sql.Timestamp.from(paidAt)).update();
        jdbc.sql("""
                        insert into payments.payout_settings (merchant_id, schedule, monthly_anchor, reserve_cents)
                        values (?, 'monthly', 'fifteenth', 50000)""").params(paid).update();
        jdbc.sql("insert into payments.payout_settings (merchant_id, schedule, reserve_cents) values (?, 'manual', 0)")
                .params(manual)
                .update();
        jdbc.sql("""
                        insert into payments.payout_accounts (id, merchant_id, method, institution_name, institution_number,
                               transit_number, last4, holder_name, external_ref, state, created_at, created_by, effective_at)
                        values (?, ?, 'manual', 'ATB Financial', '219', '00109', '7710', 'New account', 'ba_new', 'pending',
                                now(), 'u', now() + interval '1 day')""").params(Ids.next(), held).update();

        Map<String, ScheduledRunFacts> facts = payouts.scheduledRunFacts().stream()
                .collect(Collectors.toMap(ScheduledRunFacts::merchantId, Function.identity()));

        assertThat(facts).containsKeys(plain, paid, held).doesNotContainKey(manual);
        assertThat(facts.get(plain)).isEqualTo(new ScheduledRunFacts(plain, PayoutSchedule.DEFAULT, null, false));
        assertThat(facts.get(paid).schedule())
                .isEqualTo(new PayoutSchedule(
                        PayoutSchedule.Frequency.MONTHLY,
                        null,
                        PayoutSchedule.MonthlyAnchor.FIFTEENTH,
                        PayoutSchedule.Reserve.KEEP_500));
        assertThat(facts.get(paid).lastScheduledAt()).isEqualTo(paidAt);
        assertThat(facts.get(held).pendingAccount()).isTrue();
        assertThat(facts.get(plain).pendingAccount()).isFalse();
    }
}
