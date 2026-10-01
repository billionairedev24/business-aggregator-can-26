package ca.northline.console.application;

import ca.northline.merchants.api.SellerDirectory;
import ca.northline.payments.api.FinanceFigures;
import ca.northline.region.api.Regions;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ViewFinance} from payments' figures, merchants' tiers and the platform zone (region model). */
@Service
@RequiredArgsConstructor
class FinanceService implements ViewFinance {

    static final List<String> TIERS = List.of("master", "trusted", "registered");

    private final Clock clock;
    private final Regions regions;
    private final FinanceFigures figures;
    private final SellerDirectory sellers;

    @Override
    @Transactional(readOnly = true)
    public Finance finance() {
        var now = clock.instant();
        var zone = regions.platformZone();
        var from = now.minus(Duration.ofDays(7));
        var held = figures.escrowHeld();
        var inFlight = figures.payoutsInFlight();
        var revenue = figures.revenue(from, now);
        var byMerchant = figures.heldByMerchant(from, now);
        var tiers = sellers.tiers();
        var counts = new HashMap<String, Long>();
        tiers.values().forEach(t -> counts.merge(t, 1L, Long::sum));
        var gmv = new HashMap<String, Long>();
        byMerchant.forEach((id, cents) -> {
            var tier = tiers.get(id);
            if (tier != null) {
                gmv.merge(tier, cents, Long::sum);
            }
        });
        long total = gmv.values().stream().mapToLong(Long::longValue).sum();
        var rates = figures.defaultTakeRates();
        var rows = TIERS.stream()
                .map(t -> new TierRow(
                        t,
                        counts.getOrDefault(t, 0L),
                        rates.getOrDefault(t, 0),
                        total == 0 ? null : gmv.getOrDefault(t, 0L) / (double) total))
                .toList();
        var today = LocalDate.now(clock.withZone(zone));
        var quarter = today.get(IsoFields.QUARTER_OF_YEAR);
        var period = today.getYear() + "-Q" + quarter;
        var quarterEnd = LocalDate.of(today.getYear(), quarter * 3, 1).with(TemporalAdjusters.lastDayOfMonth());
        var tax = figures.tax(period);
        return new Finance(
                now,
                zone.getId(),
                held.cents(),
                held.items(),
                inFlight.cents(),
                inFlight.merchants(),
                inFlight.nextArrival(),
                revenue.total(),
                new Mix(revenue.takeCents(), revenue.deliveryCents(), revenue.adjustmentsCents(), null, null),
                rows,
                new Tax(
                        period,
                        tax.platformFeeCents(),
                        tax.facilitatorCents(),
                        quarterEnd.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())));
    }
}
