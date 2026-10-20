package ca.northline.fulfilment.application;

import ca.northline.fulfilment.application.DispatchUseCases.CourierEarnings;
import ca.northline.fulfilment.application.DispatchUseCases.EarningsView;
import ca.northline.fulfilment.application.DispatchUseCases.RunEarning;
import ca.northline.fulfilment.application.DispatchUseCases.Totals;
import ca.northline.fulfilment.application.EarningsStore.Earning;
import ca.northline.payments.api.CourierPay;
import ca.northline.region.api.Regions;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CourierEarnings} (S-151): the runs fulfilment paid ({@code courier_earnings}) and the tips payments allocated
 * to the courier, each tip counted on the run that delivered its order (a tip after delivery too). Today and this week
 * are in the courier's market's time zone (the region model; the platform's when the market isn't in it).
 */
@Service
@RequiredArgsConstructor
@Transactional
class CourierEarningsService implements CourierEarnings {

    private final CourierStore couriers;
    private final EarningsStore earnings;
    private final RunStore runs;
    private final CourierPay pay;
    private final Regions regions;
    private final Clock clock;

    @Override
    public EarningsView earnings(String userId) {
        var courier =
                couriers.byUser(userId).filter(CourierStore.Courier::active).orElseThrow(NotACourier::new);
        var zone = regions.zone(null, courier.market());
        var today = LocalDate.now(clock.withZone(zone));
        var dayStart = today.atStartOfDay(zone).toInstant();
        var weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone)
                .toInstant();
        var from = weekStart.minus(java.time.Duration.ofDays(7)); // this week and last
        var to = clock.instant().plusSeconds(1);
        var paid = earnings.of(userId, from, to);
        var tipsByRun = tipsByRun(userId, paid, from, to);
        var list = paid.stream()
                .map(e -> new RunEarning(
                        e.runId(),
                        runs.find(e.runId()).map(RunStore.Run::label).orElse(null),
                        e.earnedAt(),
                        e.totalCents(),
                        tipsByRun.getOrDefault(e.runId(), 0L),
                        e.pickups(),
                        e.dropoffs(),
                        e.failed(),
                        e.collects(),
                        e.returns()))
                .toList();
        var status = pay.status(userId);
        return new EarningsView(
                zone.getId(),
                totals(list, dayStart),
                totals(list, weekStart),
                list,
                status.balanceCents(),
                status.account(),
                status.payouts());
    }

    @Override
    public String payoutOnboarding(String userId, String returnUrl) {
        if (couriers.byUser(userId).filter(CourierStore.Courier::active).isEmpty()) {
            throw new NotACourier();
        }
        return pay.onboardingLink(userId, returnUrl, returnUrl);
    }

    /** Each tip on the run whose drop-off delivered its order. */
    private Map<String, Long> tipsByRun(String userId, List<Earning> paid, Instant from, Instant to) {
        var runOfOrder = new HashMap<String, String>();
        for (var e : paid) {
            runs.stops(e.runId()).stream()
                    .filter(s -> s.kind().equals("dropoff") && s.state().equals("done"))
                    .forEach(s -> runOfOrder.put(s.orderId(), e.runId()));
        }
        var out = new HashMap<String, Long>();
        // a tip after delivery may come days later: read tips up to now for the runs shown
        for (var t : pay.tips(userId, from, to)) {
            var run = runOfOrder.get(t.orderId());
            if (run != null) {
                out.merge(run, t.cents(), Long::sum);
            }
        }
        return out;
    }

    private static Totals totals(List<RunEarning> runs, Instant since) {
        var shown = runs.stream().filter(r -> !r.earnedAt().isBefore(since)).toList();
        return new Totals(
                shown.stream().mapToLong(RunEarning::payCents).sum(),
                shown.stream().mapToLong(RunEarning::tipsCents).sum(),
                shown.size(),
                shown.stream()
                        .mapToInt(r -> r.dropoffs() + r.failed() + r.collects())
                        .sum());
    }
}
