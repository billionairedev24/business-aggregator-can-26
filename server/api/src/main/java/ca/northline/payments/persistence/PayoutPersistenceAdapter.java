package ca.northline.payments.persistence;

import ca.northline.payments.application.PayoutRepository;
import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PayoutPersistenceAdapter implements PayoutRepository {

    private final PayoutRowRepository payouts;
    private final PayoutAccountRowRepository accounts;
    private final PaymentsRowMapper mapper;
    private final JdbcClient jdbc;

    @Override
    public void insert(Payout payout) {
        payouts.save(mapper.toRow(payout));
    }

    @Override
    public void update(Payout payout) {
        payouts.save(mapper.toRow(payout));
    }

    @Override
    public Optional<Payout> byStripePayout(String stripePayout) {
        return payouts.findByStripePayout(stripePayout).map(mapper::toDomain);
    }

    @Override
    public List<Payout> history(String merchantId, int limit) {
        return payouts.history(merchantId, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Payout> inTransit(int limit) {
        return payouts.inTransit(limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<PayoutSchedule> schedule(String merchantId) {
        return jdbc.sql("""
                        select schedule, weekday, monthly_anchor, reserve_cents, reserve_percent
                          from payments.payout_settings where merchant_id = :id""")
                .param("id", merchantId)
                .query((rs, _) -> schedule(rs))
                .optional();
    }

    private static PayoutSchedule schedule(ResultSet rs) throws SQLException {
        var weekday = rs.getObject("weekday", Integer.class);
        var reserveCents = rs.getLong("reserve_cents");
        var reservePercent = rs.getObject("reserve_percent", Integer.class);
        var reserve = reservePercent != null && reservePercent > 0
                ? PayoutSchedule.Reserve.PERCENT_10
                : reserveCents > 0 ? PayoutSchedule.Reserve.KEEP_500 : PayoutSchedule.Reserve.NONE;
        var frequency = CodedEnum.fromCode(PayoutSchedule.Frequency.class, rs.getString("schedule"));
        return new PayoutSchedule(
                frequency,
                frequency == PayoutSchedule.Frequency.WEEKLY && weekday == null ? Integer.valueOf(5) : weekday,
                CodedEnums.fromCode(rs.getString("monthly_anchor"), PayoutSchedule.MonthlyAnchor.class),
                reserve);
    }

    @Override
    public void saveSchedule(String merchantId, PayoutSchedule schedule, String userId, Instant now) {
        jdbc.sql("""
                        insert into payments.payout_settings
                               (merchant_id, schedule, weekday, monthly_anchor, reserve_cents, reserve_percent, updated_at, updated_by)
                        values (:id, :schedule, :weekday, :anchor, :reserveCents, :reservePercent, :now, :user)
                        on conflict (merchant_id) do update
                           set schedule = excluded.schedule, weekday = excluded.weekday,
                               monthly_anchor = excluded.monthly_anchor, reserve_cents = excluded.reserve_cents,
                               reserve_percent = excluded.reserve_percent, updated_at = excluded.updated_at,
                               updated_by = excluded.updated_by""")
                .param("id", merchantId)
                .param("schedule", schedule.frequency().code())
                .param("weekday", schedule.weekday())
                .param("anchor", CodedEnums.toCode(schedule.monthlyAnchor()))
                .param("reserveCents", schedule.reserve() == PayoutSchedule.Reserve.KEEP_500 ? 50_000L : 0L)
                .param("reservePercent", schedule.reserve() == PayoutSchedule.Reserve.PERCENT_10 ? 10 : null)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("user", userId)
                .update();
    }

    @Override
    public List<ScheduledRunFacts> scheduledRunFacts() {
        return jdbc.sql("""
                        select c.merchant_id, s.schedule, s.weekday, s.monthly_anchor, s.reserve_cents, s.reserve_percent,
                               (select max(p.created_at) from payments.payouts p
                                 where p.merchant_id = c.merchant_id and p.kind = 'scheduled') as last_scheduled_at,
                               exists (select 1 from payments.payout_accounts a
                                        where a.merchant_id = c.merchant_id and a.state = 'pending') as pending_account
                          from payments.connected_accounts c
                          left join payments.payout_settings s on s.merchant_id = c.merchant_id
                         where s.schedule is distinct from 'manual'
                         order by c.merchant_id""")
                .query((rs, _) -> new ScheduledRunFacts(
                        rs.getString("merchant_id"),
                        rs.getString("schedule") == null ? PayoutSchedule.DEFAULT : schedule(rs),
                        JdbcTimes.instant(rs, "last_scheduled_at"),
                        rs.getBoolean("pending_account")))
                .list();
    }

    @Override
    public void bankChanged(String merchantId, Instant at) {
        jdbc.sql("""
                        insert into payments.payout_settings (merchant_id, schedule, weekday, reserve_cents, bank_changed_at)
                        values (:id, 'weekly', 5, 0, :at)
                        on conflict (merchant_id) do update set bank_changed_at = excluded.bank_changed_at""")
                .param("id", merchantId)
                .param("at", at.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<ConnectedAccount> connectedAccount(String merchantId) {
        return jdbc.sql("""
                        select merchant_id, stripe_account, instant_payouts, coalesce(payouts_enabled, true) payouts_enabled
                          from payments.connected_accounts where merchant_id = :id""")
                .param("id", merchantId)
                .query((rs, _) -> new ConnectedAccount(
                        rs.getString("merchant_id"),
                        rs.getString("stripe_account"),
                        rs.getBoolean("instant_payouts"),
                        rs.getBoolean("payouts_enabled")))
                .optional();
    }

    @Override
    public boolean linkConnectedAccount(String merchantId, String stripeAccount) {
        return jdbc.sql("""
                        insert into payments.connected_accounts (merchant_id, stripe_account) values (:id, :acct)
                        on conflict do nothing""")
                        .param("id", merchantId)
                        .param("acct", stripeAccount)
                        .update()
                > 0;
    }

    @Override
    public Optional<String> updateConnectedAccount(AccountStatus status) {
        var merchantId = status.merchantId();
        if (merchantId != null) {
            jdbc.sql("""
                            insert into payments.connected_accounts (merchant_id, stripe_account) values (:id, :acct)
                            on conflict do nothing""")
                    .param("id", merchantId)
                    .param("acct", status.stripeAccount())
                    .update();
        }
        return jdbc.sql("""
                        update payments.connected_accounts
                           set charges_enabled = :charges, payouts_enabled = :payouts,
                               instant_payouts = coalesce(cast(:instant as boolean), instant_payouts),
                               requirements_due = :due, requirements_past_due = :pastDue,
                               disabled_reason = cast(:disabled as text), stripe_updated_at = :at
                         where stripe_account = :acct and (stripe_updated_at is null or stripe_updated_at <= :at)
                        returning merchant_id""")
                .param("charges", status.chargesEnabled())
                .param("payouts", status.payoutsEnabled())
                .param("instant", status.instantPayouts())
                .param("due", status.requirementsDue())
                .param("pastDue", status.requirementsPastDue())
                .param("disabled", status.disabledReason())
                .param("at", status.at().atOffset(java.time.ZoneOffset.UTC))
                .param("acct", status.stripeAccount())
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<PayoutAccount> account(String merchantId, String accountId) {
        return accounts.findByIdAndMerchantId(accountId, merchantId).map(mapper::toDomain);
    }

    @Override
    public Optional<PayoutAccount> activeAccount(String merchantId) {
        return byState(merchantId, PayoutAccount.State.ACTIVE);
    }

    @Override
    public Optional<PayoutAccount> pendingAccount(String merchantId) {
        return byState(merchantId, PayoutAccount.State.PENDING);
    }

    private Optional<PayoutAccount> byState(String merchantId, PayoutAccount.State state) {
        return accounts.findFirstByMerchantIdAndState(merchantId, state.code()).map(mapper::toDomain);
    }

    @Override
    public List<PayoutAccount> dueAccounts(Instant now) {
        return accounts.findByStateAndEffectiveAtLessThanEqual(PayoutAccount.State.PENDING.code(), now).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void insertAccount(PayoutAccount account) {
        accounts.save(mapper.toRow(account));
    }

    @Override
    public void updateAccount(PayoutAccount account) {
        accounts.save(mapper.toRow(account));
    }

    @Override
    public List<PayoutAccount> accountsLinkedTo(String financialConnectionsAccount) {
        return accounts.findByFinancialConnectionsAccount(financialConnectionsAccount).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public int releasedSince(String merchantId, @Nullable Instant since) {
        return jdbc.sql("""
                        select count(*) from payments.escrows
                         where merchant_id = :id and state = 'released'
                           and (cast(:since as timestamptz) is null or released_at > :since)""")
                .param("id", merchantId)
                .param("since", since == null ? null : since.atOffset(java.time.ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<Instant> lastPayoutAt(String merchantId) {
        return payouts.findFirstByMerchantIdOrderByCreatedAtDesc(merchantId).map(PayoutRow::createdAt);
    }
}
