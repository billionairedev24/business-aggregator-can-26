package ca.northline.food.application;

import ca.northline.food.api.KitchenAvailability;
import ca.northline.food.domain.KitchenCalendar;
import ca.northline.food.domain.OpeningRanges;
import ca.northline.region.api.Markets;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link KitchenAvailability} from the kitchens' settings, hours, holidays, late tickets and live menus. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class KitchenAvailabilityService implements KitchenAvailability {

    private final KitchenCalendarStore store;
    private final Clock clock;
    private final Markets markets;

    @Override
    public Map<String, KitchenStatus> now(Collection<Kitchen> kitchens) {
        var out = new HashMap<String, KitchenStatus>();
        var zones = new HashMap<String, ZoneId>();
        kitchens.forEach(k -> {
            out.put(k.merchantId(), KitchenStatus.CLOSED);
            zones.put(k.merchantId(), markets.zone(k.province()));
        });
        if (kitchens.isEmpty()) {
            return out;
        }
        var now = clock.instant();
        // holidays from yesterday (UTC) cover "today" in every Canadian zone
        var from = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
        for (var row : store.calendars(zones.keySet(), from, now)) {
            var state = calendar(row, zones.getOrDefault(row.merchantId(), markets.zone(null)))
                    .at(now);
            out.put(
                    row.merchantId(),
                    new KitchenStatus(
                            state.open(),
                            state.opensAt(),
                            state.closesAt(),
                            state.paused(),
                            row.fulfilment(),
                            row.defaultPrepMin() + row.prepBumpMin()));
        }
        return out;
    }

    static KitchenCalendar calendar(KitchenCalendarStore.CalendarRow row, ZoneId zone) {
        return new KitchenCalendar(
                zone,
                row.week().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> ranges(e.getValue()))),
                row.holidays().entrySet().stream()
                        .collect(Collectors.<Map.Entry<LocalDate, List<List<String>>>, LocalDate, OpeningRanges>toMap(
                                Map.Entry::getKey, e -> ranges(e.getValue()))),
                row.pausedUntil(),
                row.autoPauseLate(),
                row.lateOrders(),
                row.menuLive());
    }

    /** Stored ranges were checked when saved; anything unreadable counts as closed. */
    private static OpeningRanges ranges(List<List<String>> raw) {
        return OpeningRanges.check("ranges", raw, new ArrayList<RuleViolation.Violation>());
    }
}
