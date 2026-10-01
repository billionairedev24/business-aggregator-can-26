package ca.northline.payments.application;

import ca.northline.payments.api.EarningsQuery;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.domain.PayoutSchedule;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Earnings screen + the dashboard's earnings read model. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class EarningsService implements ViewEarnings, EarningsQuery {

    private final EarningsReadModel earnings;
    private final PayoutRepository payouts;
    private final MerchantBalances balances;
    private final MerchantTiers tiers;
    private final Clock clock;
    private final BusinessTime time;

    @Override
    public Overview overview(String merchantId) {
        var now = clock.instant();
        var schedule = payouts.schedule(merchantId).orElse(PayoutSchedule.DEFAULT);
        var pausedUntil =
                payouts.pendingAccount(merchantId).map(a -> a.getEffectiveAt()).orElse(null);
        var next = schedule.nextAfter(now, pausedUntil, time.of(merchantId)).orElse(null);
        var totals = earnings.escrowTotals(merchantId, next);
        var available = balances.of(merchantId);
        var rate = tiers.rateOf(merchantId);
        return new Overview(
                available.availableCents() + totals.releasingNetCents(),
                available.availableCents(),
                totals.heldNetCents(),
                totals.heldCount(),
                totals.onHoldGrossCents() + available.heldForCasesCents(),
                totals.onHoldCount(),
                available.heldCases(),
                next,
                schedule.frequency(),
                rate.tier(),
                rate.takeRateBps());
    }

    @Override
    public Releasing releasing(String merchantId) {
        var o = overview(merchantId);
        return new Releasing(o.headlineCents(), o.nextPayoutAt());
    }

    @Override
    public List<EarningsReadModel.LedgerLine> ledger(String merchantId, int limit) {
        return earnings.ledger(merchantId, Math.clamp(limit, 1, 1000));
    }

    @Override
    public List<WeekNet> weeklyNet(String merchantId, int weeks) {
        var zone = time.of(merchantId);
        var today = LocalDate.ofInstant(clock.instant(), zone);
        var firstMonday =
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L);
        var buckets = new LinkedHashMap<LocalDate, EnumMap<EscrowKind, Long>>();
        for (int i = 0; i < weeks; i++) {
            buckets.put(firstMonday.plusWeeks(i), new EnumMap<>(EscrowKind.class));
        }
        var from = firstMonday.atStartOfDay(zone).toInstant();
        for (var r : earnings.released(merchantId, from, clock.instant())) {
            var monday =
                    LocalDate.ofInstant(r.releasedAt(), zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            var bucket = buckets.get(monday);
            if (bucket != null) {
                bucket.merge(r.kind(), r.netCents(), Long::sum);
            }
        }
        var out = new ArrayList<WeekNet>(weeks);
        buckets.forEach((monday, b) -> out.add(new WeekNet(
                monday,
                b.getOrDefault(EscrowKind.SERVICE, 0L),
                b.getOrDefault(EscrowKind.GOODS, 0L),
                b.getOrDefault(EscrowKind.FOOD, 0L))));
        return out;
    }

    @Override
    public MonthNet monthNet(String merchantId) {
        var now = clock.instant();
        var zone = time.of(merchantId);
        var today = LocalDate.ofInstant(now, zone);
        var monthStart = today.withDayOfMonth(1);
        var previousStart = monthStart.minusMonths(1);
        var previousEnd = previousStart.plusDays(Math.min(today.getDayOfMonth(), previousStart.lengthOfMonth()));
        var current = sum(merchantId, monthStart, today.plusDays(1), zone);
        var previous = sum(merchantId, previousStart, previousEnd, zone);
        Integer change = previous == 0 ? null : (int) Math.round((current - previous) * 100.0 / previous);
        return new MonthNet(current, previous, change);
    }

    private long sum(String merchantId, LocalDate from, LocalDate toExclusive, ZoneId zone) {
        return earnings
                .released(
                        merchantId,
                        from.atStartOfDay(zone).toInstant(),
                        toExclusive.atStartOfDay(zone).toInstant())
                .stream()
                .mapToLong(EarningsReadModel.Released::netCents)
                .sum();
    }
}
