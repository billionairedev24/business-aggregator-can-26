package ca.northline.orders.application;

import ca.northline.orders.api.DeliveryRuns;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeliveryRuns} from {@link DeliveryProperties}: each market gets every configured run each day. Asking for
 * the upcoming runs creates the windows of today and the next two days if they don't exist yet (idempotent), in a
 * transaction of its own so read-only callers (the public Shop pages) can ask.
 */
@Service
@EnableConfigurationProperties(DeliveryProperties.class)
class DeliverySchedule implements DeliveryRuns {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final int DAYS_AHEAD = 2;

    private final DeliveryProperties properties;
    private final DeliveryWindowStore windows;

    DeliverySchedule(DeliveryProperties properties, DeliveryWindowStore windows) {
        this.properties = properties;
        this.windows = windows;
    }

    @Override
    public Optional<String> market(String city) {
        var wanted = city.strip().toLowerCase(Locale.ROOT);
        return properties.markets().stream()
                .filter(m -> m.toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Run> upcoming(String market, Instant now) {
        var name = market(market).orElse(null);
        if (name == null) {
            return List.of();
        }
        var today = LocalDate.ofInstant(now, ZONE);
        for (var day = today; !day.isAfter(today.plusDays(DAYS_AHEAD)); day = day.plusDays(1)) {
            for (var slot : properties.runs()) {
                var startsAt = day.atTime(slot.starts()).atZone(ZONE).toInstant();
                var cutoffDay = slot.cutoff().isAfter(slot.starts()) ? day.minusDays(1) : day;
                var packBy = cutoffDay.atTime(slot.cutoff()).atZone(ZONE).toInstant();
                if (packBy.minus(properties.orderLead()).isAfter(now)) {
                    var endsAt = day.atTime(slot.ends()).atZone(ZONE).toInstant();
                    windows.ensure(name, slot.name(), startsAt, endsAt, packBy, slot.capacity());
                }
            }
        }
        var until = today.plusDays(DAYS_AHEAD + 1L).atStartOfDay(ZONE).toInstant();
        return windows.between(name, now, until).stream()
                .map(w -> run(w, name))
                .filter(r -> r.openAt(now))
                .sorted(Comparator.comparing(Run::startsAt))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Run> run(String windowId) {
        return windows.byId(windowId).map(w -> run(w, Objects.requireNonNullElse(w.market(), "")));
    }

    @Override
    public Direct direct() {
        return new Direct(properties.direct().eta(), properties.direct().feeCents());
    }

    /** A window becomes a run: the fee of its slot (by name, else by start time), order-by = pack-by − lead. */
    private Run run(DeliveryWindowStore.Window w, String market) {
        var local = w.startsAt().atZone(ZONE).toLocalTime();
        var slot = properties.runs().stream()
                .filter(s -> s.name().equals(w.slot()))
                .findFirst()
                .or(() -> properties.runs().stream()
                        .filter(s -> s.starts().equals(local))
                        .findFirst());
        return new Run(
                w.id(),
                market,
                slot.map(DeliveryProperties.RunSlot::name).orElse("other"),
                w.label(),
                w.startsAt(),
                w.endsAt(),
                w.packBy().minus(properties.orderLead()),
                w.packBy(),
                slot.map(DeliveryProperties.RunSlot::feeCents)
                        .orElseGet(() -> properties.runs().getFirst().feeCents()),
                w.households());
    }
}
